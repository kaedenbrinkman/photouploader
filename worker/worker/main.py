#!/usr/bin/env python3
"""EC2 worker entrypoint."""

from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import boto3

from gphotos.gphotos_upload import MediaUploadItem
from worker.config import WorkerConfig
from worker.disk_space import require_free_bytes
from worker.dynamo_store import DynamoStore
from worker.failure_report import format_last_error
from worker.gphotos_client import upload_shoot_media
from worker.lightcraft_runner import (
    LightcraftRenderError,
    PreviewOnlyError,
    render_jpeg,
    validate_jpeg,
)
from worker.file_keys import file_dynamo_key
from worker.media_kind import (
    content_type_for_name,
    is_video_manifest_entry,
    raw_extension_for_name,
    split_manifest_files,
)

log = logging.getLogger("worker")

PIPELINE_WINDOW = max(1, min(8, int(os.environ.get("PIPELINE_WINDOW", "6"))))


def worker_log_path() -> Path:
    install_root = os.environ.get("INSTALL_ROOT", "/opt/photopipeline")
    return Path(install_root) / "worker.log"


def setup_logging(log_path: Path) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.handlers.clear()
    stream = logging.StreamHandler()
    stream.setFormatter(fmt)
    root.addHandler(stream)
    file_handler = logging.FileHandler(log_path, encoding="utf-8")
    file_handler.setFormatter(fmt)
    root.addHandler(file_handler)


def load_manifest(s3, bucket: str, shoot_id: str) -> dict:
    key = f"shoots/{shoot_id}/manifest.json"
    obj = s3.get_object(Bucket=bucket, Key=key)
    return json.loads(obj["Body"].read())


def raw_s3_key(shoot_id: str, entry: dict) -> str:
    if entry.get("s3_key"):
        return str(entry["s3_key"])
    sha = str(entry["sha256"]).lower()
    ext = raw_extension_for_name(entry.get("original_name"))
    return f"shoots/{shoot_id}/raw/{sha}{ext}"


def _safe_unlink(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except OSError:
        log.warning("failed to delete %s", path)


def download_one_raw(
    s3,
    bucket: str,
    shoot_id: str,
    entry: dict,
    raw_dir: Path,
    work_dir: Path,
) -> Path:
    sha = entry["sha256"].lower()
    ext = raw_extension_for_name(entry.get("original_name"))
    key = raw_s3_key(shoot_id, entry)
    dest = raw_dir / f"{sha}{ext}"
    size = int(entry.get("size_bytes") or 0)
    require_free_bytes(work_dir, size * 2)
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        s3.download_file(bucket, key, str(dest))
    return dest


def shutdown_instance():
    try:
        subprocess.run(["sudo", "shutdown", "-h", "now"], check=False, timeout=5)
    except Exception:
        log.exception("shutdown failed")


def manage_shutdown_backstop(*, cancel: bool, arm_idle_minutes: int | None = None) -> None:
    try:
        if cancel:
            subprocess.run(["sudo", "shutdown", "-c"], check=False, timeout=5)
        if arm_idle_minutes is not None and arm_idle_minutes > 0:
            subprocess.run(
                ["sudo", "shutdown", "-h", f"+{arm_idle_minutes}", "photopipeline worker idle backstop"],
                check=False,
                timeout=5,
            )
    except Exception:
        log.exception("shutdown backstop update failed")


def _count_rendered_photos(store: DynamoStore, shoot_id: str, photo_keys: set[str]) -> int:
    n = 0
    for file_key in photo_keys:
        st = (store.get_image(shoot_id, file_key) or {}).get("state")
        if st in ("RENDERED", "GP_CREATED", "PUBLISHED"):
            n += 1
    return n


def _file_error(store: DynamoStore, shoot_id: str, sha: str, message: str, state: str) -> None:
    store.update_image(
        shoot_id,
        sha,
        state=state,
        error=message,
        lastError=message,
    )


def run() -> int:
    log_path = worker_log_path()
    setup_logging(log_path)
    cfg = WorkerConfig.from_env()
    s3 = boto3.client("s3", region_name=cfg.region)
    store = DynamoStore(cfg.dynamodb_table, cfg.region)
    work = Path(cfg.work_dir)
    raw_dir = work / "raw"
    out_dir = work / "out"
    ledger_path = work / "gphotos_ledger.json"
    raw_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)

    shoot_meta = store.get_shoot(cfg.shoot_id) or {}
    store.update_shoot(
        cfg.shoot_id,
        state="PROCESSING",
        phase="RENDERING",
        totalFiles=0,
        renderedCount=0,
        publishedCount=shoot_meta.get("publishedCount") or 0,
    )
    manage_shutdown_backstop(cancel=True, arm_idle_minutes=None)

    try:
        manifest = load_manifest(s3, cfg.bucket, cfg.shoot_id)
        all_files = manifest.get("files", [])
        photo_entries, video_entries, _video_bytes = split_manifest_files(all_files)
        photo_file_keys = {file_dynamo_key(cfg.shoot_id, f, raw_s3_key) for f in photo_entries}
        video_file_keys = {file_dynamo_key(cfg.shoot_id, f, raw_s3_key) for f in video_entries}
        video_content_sha_to_key = {
            f["sha256"].lower(): file_dynamo_key(cfg.shoot_id, f, raw_s3_key) for f in video_entries
        }
        total = len(all_files)
        photo_count = len(photo_entries)
        video_count = len(video_entries)
        store.update_shoot(
            cfg.shoot_id,
            totalFiles=total,
            photoCount=photo_count,
            videoCount=video_count,
            phase="RENDERING",
        )
        for entry in all_files:
            file_key = file_dynamo_key(cfg.shoot_id, entry, raw_s3_key)
            media_type = "video" if is_video_manifest_entry(entry) else "photo"
            store.update_image(
                cfg.shoot_id,
                file_key,
                mediaType=media_type,
                originalName=entry.get("original_name"),
                contentSha256=entry["sha256"].lower(),
            )

        ledger = json.loads(ledger_path.read_text()) if ledger_path.exists() else {"album_id": None, "items": {}}
        if shoot_meta.get("albumId") and not ledger.get("album_id"):
            ledger["album_id"] = shoot_meta["albumId"]
        if shoot_meta.get("albumProductUrl") and not ledger.get("album_product_url"):
            ledger["album_product_url"] = shoot_meta["albumProductUrl"]

        def save_ledger():
            tmp = ledger_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(ledger, indent=1))
            tmp.replace(ledger_path)

        render_started_at = int(time.time() * 1000)
        store.update_shoot(cfg.shoot_id, phase="RENDERING", renderStartedAt=render_started_at)
        first_error: str | None = None
        fail_count = 0
        pipeline_workers = max(1, min(PIPELINE_WINDOW, cfg.max_parallel_renders))

        def pipeline_one_photo(entry: dict) -> tuple[str, str | None]:
            file_key = file_dynamo_key(cfg.shoot_id, entry, raw_s3_key)
            content_sha = entry["sha256"].lower()
            existing = store.get_image(cfg.shoot_id, file_key)
            if existing and existing.get("state") in ("GP_CREATED", "PUBLISHED"):
                return content_sha, None
            raw: Path | None = None
            out = out_dir / f"{content_sha}.jpg"
            jpeg_key = f"shoots/{cfg.shoot_id}/jpeg/{cfg.settings_hash}/{content_sha}.jpg"
            try:
                if existing and existing.get("state") == "RENDERED" and not out.exists():
                    try:
                        s3.download_file(cfg.bucket, jpeg_key, str(out))
                    except Exception:
                        pass
                needs_render = not out.exists()
                if needs_render:
                    raw = download_one_raw(s3, cfg.bucket, cfg.shoot_id, entry, raw_dir, work)
                    render_jpeg(
                        cfg.lightcraft_bin,
                        raw,
                        out,
                        settings_hash=cfg.settings_hash,
                    )
                    validate_jpeg(out)
                    store.update_image(
                        cfg.shoot_id,
                        file_key,
                        state="RENDERED",
                        settingsHash=cfg.settings_hash,
                        mediaType="photo",
                    )
                    s3.upload_file(str(out), cfg.bucket, jpeg_key)
                upload_shoot_media(
                    cfg.gphotos_token_param,
                    cfg.region,
                    cfg.album_title,
                    [],
                    [out],
                    ledger,
                    save_ledger,
                    s3_client=s3,
                )
                item = ledger.get("items", {}).get(content_sha, {})
                if item.get("state") == "CREATED":
                    store.update_image(
                        cfg.shoot_id,
                        file_key,
                        state="GP_CREATED",
                        mediaType="photo",
                        mediaItemId=item.get("media_item_id"),
                    )
                return content_sha, None
            except PreviewOnlyError as e:
                _file_error(store, cfg.shoot_id, file_key, str(e), "PREVIEW_ONLY")
                return content_sha, str(e)
            except LightcraftRenderError as e:
                _file_error(store, cfg.shoot_id, file_key, str(e), "FAILED_RENDER")
                return content_sha, str(e)
            finally:
                if raw is not None:
                    _safe_unlink(raw)
                _safe_unlink(out)

        if photo_entries:
            with ThreadPoolExecutor(max_workers=pipeline_workers) as pool:
                futures = [pool.submit(pipeline_one_photo, entry) for entry in photo_entries]
                for fut in as_completed(futures):
                    sha, err = fut.result()
                    if err:
                        fail_count += 1
                        if first_error is None:
                            first_error = err
                    rendered_count = _count_rendered_photos(store, cfg.shoot_id, photo_file_keys)
                    store.update_shoot(
                        cfg.shoot_id,
                        renderedCount=rendered_count,
                        renderedPhotoCount=rendered_count,
                        phase="RENDERING",
                    )
                    log.info("pipeline photo %s err=%s (%s/%s)", sha, err, rendered_count, photo_count)

        rendered_photo_count = _count_rendered_photos(store, cfg.shoot_id, photo_file_keys)
        if photo_count > 0 and rendered_photo_count == 0:
            summary = f"{first_error or 'render failed'}; {fail_count}/{photo_count} photo(s) failed"
            store.finish_run(
                cfg.shoot_id,
                state="FAILED",
                phase="RENDER_FAILED",
                renderedCount=0,
                renderedPhotoCount=0,
                publishedCount=0,
                lastError=summary,
                workerFinishedAt=int(time.time() * 1000),
            )
            log.error("no photo renders succeeded: %s", summary)
            if video_count == 0:
                return 1

        store.update_shoot(cfg.shoot_id, state="PUBLISHING", phase="PUBLISHING_GP")

        media_items: list[MediaUploadItem] = []
        for entry in video_entries:
            file_key = file_dynamo_key(cfg.shoot_id, entry, raw_s3_key)
            content_sha = entry["sha256"].lower()
            existing = store.get_image(cfg.shoot_id, file_key)
            if existing and existing.get("state") == "PUBLISHED":
                continue
            key = raw_s3_key(cfg.shoot_id, entry)
            name = entry.get("original_name") or f"{content_sha}.mp4"
            size = int(entry.get("size_bytes") or 0)
            require_free_bytes(work, min(size, 512 * 1024 * 1024))
            media_items.append(
                MediaUploadItem(
                    ledger_key=content_sha,
                    filename=os.path.basename(name),
                    content_type=content_type_for_name(name),
                    size_bytes=size,
                    s3_bucket=cfg.bucket,
                    s3_key=key,
                )
            )

        result = upload_shoot_media(
            cfg.gphotos_token_param,
            cfg.region,
            cfg.album_title,
            media_items,
            [],
            ledger,
            save_ledger,
            s3_client=s3,
        )
        for sha, item in ledger.get("items", {}).items():
            if item.get("state") != "CREATED":
                continue
            file_key = video_content_sha_to_key.get(sha)
            if file_key:
                store.update_image(
                    cfg.shoot_id,
                    file_key,
                    state="PUBLISHED",
                    mediaType="video",
                    mediaItemId=item.get("media_item_id"),
                )

        published_count = sum(
            1
            for file_key in photo_file_keys | video_file_keys
            if (store.get_image(cfg.shoot_id, file_key) or {}).get("state") in ("GP_CREATED", "PUBLISHED")
        )

        results_key = f"shoots/{cfg.shoot_id}/results.json"
        t1 = int(time.time() * 1000)
        worker_started = shoot_meta.get("workerStartedAt")
        compute_ms = t1 - worker_started if isinstance(worker_started, int) else t1 - render_started_at
        body = {
            "shoot_id": cfg.shoot_id,
            "album_id": result.get("album_id"),
            "created": published_count,
            "settings_hash": cfg.settings_hash,
            "computeMs": compute_ms,
        }
        s3.put_object(
            Bucket=cfg.bucket,
            Key=results_key,
            Body=json.dumps(body).encode(),
            ContentType="application/json",
        )

        final_state = "DONE" if published_count >= 1 else "PARTIAL"
        album_updates: dict = {
            "state": final_state,
            "phase": final_state,
            "albumId": result.get("album_id"),
            "resultsS3Key": results_key,
            "renderedCount": min(rendered_photo_count, photo_count),
            "renderedPhotoCount": min(rendered_photo_count, photo_count),
            "publishedCount": published_count,
            "workerFinishedAt": t1,
        }
        if result.get("album_product_url"):
            album_updates["albumProductUrl"] = result.get("album_product_url")
        if final_state == "PARTIAL":
            album_updates["lastError"] = (
                f"Published {published_count}/{total}; {fail_count} photo render failure(s)"
                if fail_count
                else f"Published {published_count}/{total}"
            )
        store.finish_run(cfg.shoot_id, **album_updates)
        log.info("done: %s state=%s published=%s", body, final_state, published_count)
        return 0
    except Exception as e:
        log.exception("worker failed")
        store.finish_run(
            cfg.shoot_id,
            state="FAILED",
            phase="FAILED",
            lastError=format_last_error(str(e), log_path),
            workerFinishedAt=int(time.time() * 1000),
        )
        return 1
    finally:
        shutdown_instance()


if __name__ == "__main__":
    sys.exit(run())
