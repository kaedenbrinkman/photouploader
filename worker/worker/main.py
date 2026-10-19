#!/usr/bin/env python3
"""EC2 worker entrypoint."""

from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
import threading
import time
from typing import Any
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import boto3

from gphotos.gphotos_upload import MediaUploadItem
from worker.config import WorkerConfig
from worker.disk_space import require_free_bytes
from worker.dynamo_store import DynamoStore
from gphotos.batch_create import BatchCreateQueue
from worker.failure_report import human_last_error
from worker.gphotos_album_lock import album_creation_file_lock
from worker.gphotos_client import ensure_shoot_album, flush_batch_create_queue, upload_shoot_media
from worker.ledger_s3 import load_ledger, merge_ledger_from_file_rows, save_ledger_to_s3
from worker.publish_meta import count_publish_progress
from worker.phase_watchdog import PhaseStallWatchdog, install_diagnostic_handlers
from worker.jpeg_resume import try_fetch_existing_jpeg
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
    install_diagnostic_handlers()
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
    phase_state: dict[str, Any] = {"phase": shoot_meta.get("phase") or "BOOT"}
    stall_triggered = threading.Event()

    def _current_phase() -> str | None:
        row = store.get_shoot(cfg.shoot_id) or {}
        return (row.get("phase") or phase_state.get("phase")) if row else phase_state.get("phase")

    def _on_phase_stall(msg: str, _idle_s: float) -> None:
        if stall_triggered.is_set():
            return
        stall_triggered.set()
        store.finish_run(
            cfg.shoot_id,
            state="FAILED",
            phase="STALLED",
            lastError=human_last_error(msg),
            workerFinishedAt=int(time.time() * 1000),
        )
        log.error("phase stall: %s", msg)

    watchdog = PhaseStallWatchdog(get_phase=_current_phase, on_stall=_on_phase_stall)
    watchdog.start()

    def touch_phase(phase: str, **extra: Any) -> None:
        phase_state["phase"] = phase
        watchdog.note_phase(phase)
        if extra:
            store.update_shoot(cfg.shoot_id, phase=phase, **extra)
        else:
            store.update_shoot(cfg.shoot_id, phase=phase)

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
        store.update_shoot(cfg.shoot_id, phase="SYNCING_FILES")
        for idx, entry in enumerate(all_files, start=1):
            file_key = file_dynamo_key(cfg.shoot_id, entry, raw_s3_key)
            media_type = "video" if is_video_manifest_entry(entry) else "photo"
            store.update_image(
                cfg.shoot_id,
                file_key,
                mediaType=media_type,
                originalName=entry.get("original_name"),
                contentSha256=entry["sha256"].lower(),
            )
            if idx == 1 or idx == total or idx % 50 == 0:
                log.info("synced FILE# rows %s/%s", idx, total)

        ledger = load_ledger(
            s3_client=s3,
            bucket=cfg.bucket,
            shoot_id=cfg.shoot_id,
            local_path=ledger_path,
            meta_album_id=shoot_meta.get("albumId"),
            meta_album_url=shoot_meta.get("albumProductUrl"),
        )
        merge_ledger_from_file_rows(
            ledger,
            entries=all_files,
            file_key_for_entry=lambda e: file_dynamo_key(cfg.shoot_id, e, raw_s3_key),
            store=store,
            shoot_id=cfg.shoot_id,
        )

        # RLock: save_ledger() is invoked while ensure_album holds the ledger lock.
        ledger_lock = threading.RLock()

        def save_ledger():
            with ledger_lock:
                tmp = ledger_path.with_suffix(".tmp")
                tmp.write_text(json.dumps(ledger, indent=1))
                tmp.replace(ledger_path)
                save_ledger_to_s3(
                    s3_client=s3,
                    bucket=cfg.bucket,
                    shoot_id=cfg.shoot_id,
                    local_path=ledger_path,
                )

        def persist_album_meta(album_id: str, product_url: str | None) -> None:
            meta: dict[str, Any] = {"albumId": album_id}
            if product_url:
                meta["albumProductUrl"] = product_url
            store.update_shoot(cfg.shoot_id, **meta)

        def flush_publish_meta(*, phase: str = "PUBLISHING_GP") -> None:
            rendered, pub_photos, pub_videos, pub_total = count_publish_progress(
                store,
                cfg.shoot_id,
                photo_file_keys,
                video_file_keys,
            )
            store.update_shoot(
                cfg.shoot_id,
                state="PUBLISHING",
                phase=phase,
                renderedCount=rendered,
                renderedPhotoCount=rendered,
                publishedCount=pub_total,
                publishedPhotoCount=pub_photos,
                publishedVideoCount=pub_videos,
            )

        batch_queue = BatchCreateQueue()
        gphotos_hooks = {
            "on_album_id": persist_album_meta,
            "on_batch_created": lambda _batch, _total: flush_publish_meta(),
            "on_publish_phase": lambda: flush_publish_meta(phase="PUBLISHING_GP"),
        }

        def _sync_ledger_file_row(content_sha: str, file_key: str, media_type: str) -> None:
            with ledger_lock:
                item = ledger.get("items", {}).get(content_sha.lower(), {})
            st = item.get("state")
            if st == "CREATED":
                store.update_image(
                    cfg.shoot_id,
                    file_key,
                    state="GP_CREATED" if media_type == "photo" else "PUBLISHED",
                    mediaType=media_type,
                    mediaItemId=item.get("media_item_id"),
                )
            elif st == "FAILED_CREATE":
                err = item.get("error") or {}
                msg = err.get("message") if isinstance(err, dict) else str(err)
                store.update_image(
                    cfg.shoot_id,
                    file_key,
                    state="GP_FAILED",
                    mediaType=media_type,
                    lastError=human_last_error(msg or "Google Photos publish failed"),
                )

        def _sync_all_ledger_rows() -> None:
            for entry in photo_entries:
                fk = file_dynamo_key(cfg.shoot_id, entry, raw_s3_key)
                _sync_ledger_file_row(entry["sha256"].lower(), fk, "photo")
            for entry in video_entries:
                fk = file_dynamo_key(cfg.shoot_id, entry, raw_s3_key)
                _sync_ledger_file_row(entry["sha256"].lower(), fk, "video")
            flush_publish_meta(phase="PUBLISHING_GP")

        def _run_batch_flush(*, retry_failed: bool) -> None:
            if not needs_gphotos:
                return
            flush_batch_create_queue(
                cfg.gphotos_token_param,
                cfg.region,
                ledger,
                batch_queue,
                save_ledger,
                ledger_lock=ledger_lock,
                on_batch_created=gphotos_hooks["on_batch_created"],
                on_publish_phase=gphotos_hooks["on_publish_phase"],
                retry_failed=retry_failed,
            )
            _sync_all_ledger_rows()

        def gphotos_progress(phase: str) -> None:
            log.info("gphotos progress phase=%s", phase)
            touch_phase(phase)

        needs_gphotos = bool(photo_entries or video_entries)
        if needs_gphotos:
            album_file_lock = lambda: album_creation_file_lock(work)
            try:
                ensure_shoot_album(
                    cfg.gphotos_token_param,
                    cfg.region,
                    cfg.album_title,
                    ledger,
                    save_ledger,
                    ledger_lock=ledger_lock,
                    album_file_lock=album_file_lock,
                    on_progress=gphotos_progress,
                    on_album_id=persist_album_meta,
                )
            except Exception as e:
                msg = human_last_error(str(e))
                log.exception("gphotos ensure album failed")
                store.finish_run(
                    cfg.shoot_id,
                    state="FAILED",
                    phase="GP_FAILED",
                    lastError=msg,
                    workerFinishedAt=int(time.time() * 1000),
                )
                return 1
            log.info("gphotos album ready album_id=%s", ledger.get("album_id"))

        render_started_at = int(time.time() * 1000)
        first_error: str | None = None
        fail_count = 0
        pipeline_workers = max(1, min(PIPELINE_WINDOW, cfg.max_parallel_renders))
        store.update_shoot(cfg.shoot_id, phase="RENDERING", renderStartedAt=render_started_at)
        if shoot_meta.get("launchProfile") == "publish_only" and photo_count > 0:
            reuse_rendered = _count_rendered_photos(store, cfg.shoot_id, photo_file_keys)
            store.update_shoot(
                cfg.shoot_id,
                renderedCount=reuse_rendered,
                renderedPhotoCount=reuse_rendered,
            )
            log.info("publish_only: seeded renderedPhotoCount=%s/%s", reuse_rendered, photo_count)
        log.info(
            "pipeline start photos=%s videos=%s workers=%s",
            photo_count,
            video_count,
            pipeline_workers,
        )

        def pipeline_one_photo(entry: dict) -> tuple[str, str | None]:
            file_key = file_dynamo_key(cfg.shoot_id, entry, raw_s3_key)
            content_sha = entry["sha256"].lower()
            existing = store.get_image(cfg.shoot_id, file_key)
            if existing and existing.get("state") in ("GP_CREATED", "PUBLISHED"):
                return content_sha, None
            if existing and existing.get("state") == "GP_FAILED":
                return content_sha, existing.get("lastError")
            raw: Path | None = None
            out = out_dir / f"{content_sha}.jpg"
            jpeg_key = f"shoots/{cfg.shoot_id}/jpeg/{cfg.settings_hash}/{content_sha}.jpg"
            try:
                has_jpeg = try_fetch_existing_jpeg(s3, cfg.bucket, jpeg_key, out)
                if has_jpeg:
                    if (existing or {}).get("state") not in ("RENDERED", "GP_CREATED", "PUBLISHED"):
                        store.update_image(
                            cfg.shoot_id,
                            file_key,
                            state="RENDERED",
                            settingsHash=cfg.settings_hash,
                            mediaType="photo",
                        )
                else:
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
                    [
                        MediaUploadItem(
                            ledger_key=content_sha,
                            filename=f"{content_sha}.jpg",
                            content_type="image/jpeg",
                            size_bytes=out.stat().st_size,
                            local_path=str(out),
                        )
                    ],
                    [],
                    ledger,
                    save_ledger,
                    s3_client=s3,
                    ledger_lock=ledger_lock,
                    album_file_lock=lambda: album_creation_file_lock(work),
                    on_progress=gphotos_progress,
                    on_album_id=gphotos_hooks["on_album_id"],
                    defer_batch_create=True,
                    skip_ensure_album=True,
                    batch_queue=batch_queue,
                )
                return content_sha, None
            except PreviewOnlyError as e:
                _file_error(store, cfg.shoot_id, file_key, str(e), "PREVIEW_ONLY")
                return content_sha, str(e)
            except LightcraftRenderError as e:
                _file_error(store, cfg.shoot_id, file_key, str(e), "FAILED_RENDER")
                return content_sha, str(e)
            except Exception as e:
                log.exception("photo publish pipeline failed sha=%s", content_sha)
                short = human_last_error(str(e))
                _file_error(store, cfg.shoot_id, file_key, short, "GP_FAILED")
                return content_sha, short
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
        _run_batch_flush(retry_failed=False)

        if photo_count > 0 and rendered_photo_count == 0 and video_count == 0:
            summary = human_last_error(
                f"{first_error or 'Render failed'} ({fail_count}/{photo_count} photos)",
            )
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
            return 1

        flush_publish_meta(phase="PUBLISHING_GP")

        media_items: list[MediaUploadItem] = []
        for entry in video_entries:
            file_key = file_dynamo_key(cfg.shoot_id, entry, raw_s3_key)
            content_sha = entry["sha256"].lower()
            existing = store.get_image(cfg.shoot_id, file_key)
            if existing and existing.get("state") in ("PUBLISHED", "GP_CREATED"):
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

        if media_items:
            try:
                upload_shoot_media(
                    cfg.gphotos_token_param,
                    cfg.region,
                    cfg.album_title,
                    media_items,
                    [],
                    ledger,
                    save_ledger,
                    s3_client=s3,
                    ledger_lock=ledger_lock,
                    album_file_lock=lambda: album_creation_file_lock(work),
                    on_progress=gphotos_progress,
                    on_album_id=gphotos_hooks["on_album_id"],
                    defer_batch_create=True,
                    skip_ensure_album=True,
                    batch_queue=batch_queue,
                )
            except Exception as e:
                log.exception("video upload staging failed")
                for entry in video_entries:
                    fk = file_dynamo_key(cfg.shoot_id, entry, raw_s3_key)
                    _file_error(
                        store,
                        cfg.shoot_id,
                        fk,
                        human_last_error(str(e)),
                        "GP_FAILED",
                    )

        _run_batch_flush(retry_failed=True)
        result = {
            "album_id": ledger.get("album_id"),
            "album_product_url": ledger.get("album_product_url"),
        }
        flush_publish_meta(phase="PUBLISHING_GP")

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

        gp_failed = sum(
            1
            for fk in photo_file_keys | video_file_keys
            if (store.get_image(cfg.shoot_id, fk) or {}).get("state") == "GP_FAILED"
        )
        if published_count >= total and gp_failed == 0 and fail_count == 0:
            final_state = "DONE"
        elif published_count > 0 or gp_failed > 0 or fail_count > 0:
            final_state = "PARTIAL"
        else:
            final_state = "FAILED"
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
        if final_state in ("PARTIAL", "FAILED"):
            album_updates["lastError"] = human_last_error(
                f"Published {published_count}/{total} files"
                + (f"; {gp_failed} Google Photos failed" if gp_failed else "")
                + (f"; {fail_count} render failed" if fail_count else ""),
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
            lastError=human_last_error(str(e)),
            workerFinishedAt=int(time.time() * 1000),
        )
        return 1
    finally:
        shutdown_instance()


if __name__ == "__main__":
    sys.exit(run())
