#!/usr/bin/env python3
"""EC2 worker entrypoint."""

from __future__ import annotations

import json
import logging
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import boto3

from worker.config import WorkerConfig
from worker.dynamo_store import DynamoStore
from worker.gphotos_client import upload_shoot_jpegs
from worker.lightcraft_runner import (
    LightcraftRenderError,
    PreviewOnlyError,
    render_jpeg,
    validate_jpeg,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("worker")


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


def run() -> int:
    cfg = WorkerConfig.from_env()
    s3 = boto3.client("s3", region_name=cfg.region)
    store = DynamoStore(cfg.dynamodb_table, cfg.region)
    work = Path(cfg.work_dir)
    raw_dir = work / "raw"
    out_dir = work / "out"
    ledger_path = work / "gphotos_ledger.json"
    out_dir.mkdir(parents=True, exist_ok=True)
    t0 = int(time.time() * 1000)

    store.update_shoot(
        cfg.shoot_id,
        state="PROCESSING",
        phase="DOWNLOADING",
        workerStartedAt=t0,
        totalFiles=0,
        renderedCount=0,
        publishedCount=0,
    )
    manage_shutdown_backstop(cancel=True, arm_idle_minutes=120)

    try:
        manifest = load_manifest(s3, cfg.bucket, cfg.shoot_id)
        total = len(manifest.get("files", []))
        store.update_shoot(cfg.shoot_id, totalFiles=total, phase="DOWNLOADING")
        raws = download_raws(s3, cfg.bucket, cfg.shoot_id, manifest, raw_dir)
        store.update_shoot(cfg.shoot_id, phase="PROCESSING")
        rendered: list[Path] = []
        rendered_count = 0

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
                store.update_image(cfg.shoot_id, sha, state="PREVIEW_ONLY", error=str(e))
                return sha, None, "PREVIEW_ONLY"
            except LightcraftRenderError as e:
                store.update_image(cfg.shoot_id, sha, state="FAILED_RENDER", error=str(e))
                return sha, None, "FAILED_RENDER"

        with ThreadPoolExecutor(max_workers=cfg.max_parallel_renders) as pool:
            futures = [pool.submit(process_one, r) for r in raws]
            for fut in as_completed(futures):
                sha, out, err = fut.result()
                if out:
                    rendered.append(out)
                    rendered_count += 1
                    store.update_shoot(cfg.shoot_id, renderedCount=rendered_count, phase="PROCESSING")
                log.info("processed %s err=%s (%s/%s)", sha, err, rendered_count, total)

        store.update_shoot(cfg.shoot_id, state="PUBLISHING", phase="PUBLISHING_GP")
        ledger = json.loads(ledger_path.read_text()) if ledger_path.exists() else {"album_id": None, "items": {}}

        def save_ledger():
            tmp = ledger_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(ledger, indent=1))
            tmp.replace(ledger_path)

        published = 0

        def on_batch_done(n: int):
            nonlocal published
            published += n
            store.update_shoot(cfg.shoot_id, publishedCount=published, phase="PUBLISHING_GP")

        result = upload_shoot_jpegs(
            cfg.gphotos_token_param,
            cfg.region,
            cfg.album_title,
            rendered,
            ledger,
            save_ledger,
        )
        for sha, item in ledger.get("items", {}).items():
            if item.get("state") == "CREATED":
                store.update_image(
                    cfg.shoot_id,
                    sha,
                    state="GP_CREATED",
                    mediaItemId=item.get("media_item_id"),
                )
        store.update_shoot(cfg.shoot_id, publishedCount=len(rendered))

        results_key = f"shoots/{cfg.shoot_id}/results.json"
        t1 = int(time.time() * 1000)
        body = {
            "shoot_id": cfg.shoot_id,
            "album_id": result.get("album_id"),
            "created": result.get("created"),
            "settings_hash": cfg.settings_hash,
            "computeMs": t1 - t0,
        }
        s3.put_object(
            Bucket=cfg.bucket,
            Key=results_key,
            Body=json.dumps(body).encode(),
            ContentType="application/json",
        )
        store.update_shoot(
            cfg.shoot_id,
            state="DONE",
            phase="DONE",
            albumId=result.get("album_id"),
            resultsS3Key=results_key,
            workerFinishedAt=t1,
        )
        log.info("done: %s", body)
        return 0
    except Exception as e:
        log.exception("worker failed")
        store.update_shoot(
            cfg.shoot_id,
            state="FAILED",
            phase="FAILED",
            error=str(e),
            workerFinishedAt=int(time.time() * 1000),
        )
        return 1
    finally:
        shutdown_instance()


if __name__ == "__main__":
    sys.exit(run())
