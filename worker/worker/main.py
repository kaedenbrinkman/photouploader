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

from worker.config import WorkerConfig
from worker.dynamo_store import DynamoStore
from worker.gphotos_client import upload_shoot_jpegs
from worker.failure_report import format_last_error
from worker.lightcraft_runner import (
    LightcraftRenderError,
    PreviewOnlyError,
    render_jpeg,
    validate_jpeg,
)

log = logging.getLogger("worker")


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


def download_raws(s3, bucket: str, shoot_id: str, manifest: dict, raw_dir: Path) -> list[Path]:
    paths: list[Path] = []
    for f in manifest["files"]:
        sha = f["sha256"].lower()
        ext = ".CR3"
        name = (f.get("original_name") or "").upper()
        if name.endswith(".JPG"):
            ext = ".JPG"
        key = f.get("s3_key") or f"shoots/{shoot_id}/raw/{sha}{ext}"
        dest = raw_dir / f"{sha}{ext}"
        if not dest.exists():
            dest.parent.mkdir(parents=True, exist_ok=True)
            s3.download_file(bucket, key, str(dest))
        paths.append(dest)
    return paths


def shutdown_instance():
    try:
        subprocess.run(["sudo", "shutdown", "-h", "now"], check=False, timeout=5)
    except Exception:
        log.exception("shutdown failed")


def manage_shutdown_backstop(*, cancel: bool, arm_idle_minutes: int | None = None) -> None:
    """Cancel bootstrap backstop; optionally arm idle shutdown (matches user-data lifetime)."""
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


def _count_rendered_files(store: DynamoStore, shoot_id: str, raws: list[Path]) -> int:
    n = 0
    for raw in raws:
        st = (store.get_image(shoot_id, raw.stem.lower()) or {}).get("state")
        if st in ("RENDERED", "GP_CREATED"):
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
        total = len(manifest.get("files", []))
        store.update_shoot(cfg.shoot_id, totalFiles=total, phase="DOWNLOADING")
        raws = download_raws(s3, cfg.bucket, cfg.shoot_id, manifest, raw_dir)
        render_started_at = int(time.time() * 1000)
        store.update_shoot(cfg.shoot_id, phase="RENDERING", renderStartedAt=render_started_at)
        rendered: list[Path] = []
        already_published = sum(
            1
            for raw in raws
            if (store.get_image(cfg.shoot_id, raw.stem.lower()) or {}).get("state") == "GP_CREATED"
        )
        first_error: str | None = None
        fail_count = 0

        def process_one(raw: Path) -> tuple[str, Path | None, str | None]:
            sha = raw.stem.lower()
            existing = store.get_image(cfg.shoot_id, sha)
            if existing and existing.get("state") == "GP_CREATED":
                out = out_dir / f"{sha}.jpg"
                if out.exists():
                    return sha, out, None
            try:
                out = out_dir / f"{sha}.jpg"
                if not out.exists():
                    render_jpeg(
                        cfg.lightcraft_bin,
                        raw,
                        out,
                        settings_hash=cfg.settings_hash,
                    )
                validate_jpeg(out)
                store.update_image(
                    cfg.shoot_id,
                    sha,
                    state="RENDERED",
                    settingsHash=cfg.settings_hash,
                )
                s3.upload_file(
                    str(out),
                    cfg.bucket,
                    f"shoots/{cfg.shoot_id}/jpeg/{cfg.settings_hash}/{sha}.jpg",
                )
                return sha, out, None
            except PreviewOnlyError as e:
                _file_error(store, cfg.shoot_id, sha, str(e), "PREVIEW_ONLY")
                return sha, None, str(e)
            except LightcraftRenderError as e:
                _file_error(store, cfg.shoot_id, sha, str(e), "FAILED_RENDER")
                return sha, None, str(e)

        with ThreadPoolExecutor(max_workers=cfg.max_parallel_renders) as pool:
            futures = [pool.submit(process_one, r) for r in raws]
            for fut in as_completed(futures):
                sha, out, err = fut.result()
                if err:
                    fail_count += 1
                    if first_error is None:
                        first_error = err
                if out:
                    rendered.append(out)
                rendered_count = _count_rendered_files(store, cfg.shoot_id, raws)
                store.update_shoot(
                    cfg.shoot_id,
                    renderedCount=min(rendered_count, total),
                    phase="RENDERING",
                )
                log.info("processed %s err=%s (%s/%s)", sha, err, rendered_count, total)

        rendered_count = _count_rendered_files(store, cfg.shoot_id, raws)
        if rendered_count == 0:
            summary = f"{first_error or 'render failed'}; {fail_count}/{total} failed"
            store.update_shoot(
                cfg.shoot_id,
                state="FAILED",
                phase="RENDER_FAILED",
                renderedCount=0,
                publishedCount=0,
                lastError=summary,
                workerFinishedAt=int(time.time() * 1000),
            )
            log.error("no renders succeeded: %s", summary)
            return 1

        store.update_shoot(cfg.shoot_id, state="PUBLISHING", phase="PUBLISHING_GP")
        ledger = json.loads(ledger_path.read_text()) if ledger_path.exists() else {"album_id": None, "items": {}}
        if shoot_meta.get("albumId") and not ledger.get("album_id"):
            ledger["album_id"] = shoot_meta["albumId"]
        if shoot_meta.get("albumProductUrl") and not ledger.get("album_product_url"):
            ledger["album_product_url"] = shoot_meta["albumProductUrl"]

        def save_ledger():
            tmp = ledger_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(ledger, indent=1))
            tmp.replace(ledger_path)

        result = upload_shoot_jpegs(
            cfg.gphotos_token_param,
            cfg.region,
            cfg.album_title,
            rendered,
            ledger,
            save_ledger,
        )
        newly_published = 0
        for sha, item in ledger.get("items", {}).items():
            if item.get("state") == "CREATED":
                newly_published += 1
                store.update_image(
                    cfg.shoot_id,
                    sha,
                    state="GP_CREATED",
                    mediaItemId=item.get("media_item_id"),
                )
        published_count = already_published + newly_published

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
            "renderedCount": min(rendered_count, total),
            "publishedCount": published_count,
            "workerFinishedAt": t1,
        }
        if result.get("album_product_url"):
            album_updates["albumProductUrl"] = result.get("album_product_url")
        if final_state == "PARTIAL":
            album_updates["lastError"] = (
                f"Published {published_count}/{total}; {fail_count} render failure(s)"
                if fail_count
                else f"Published {published_count}/{total}"
            )
        store.update_shoot(cfg.shoot_id, **album_updates)
        log.info("done: %s state=%s published=%s", body, final_state, published_count)
        return 0
    except Exception as e:
        log.exception("worker failed")
        store.update_shoot(
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
