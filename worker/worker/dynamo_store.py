"""DynamoDB shoot/image status updates."""

from __future__ import annotations

import time
from typing import Any

import boto3


def compute_finish_snapshot(meta: dict[str, Any], finished_at_ms: int) -> dict[str, Any]:
    from photopipeline.pricing import actual_compute_cost

    started = meta.get("workerStartedAt")
    instance_type = meta.get("instanceType")
    out: dict[str, Any] = {"workerFinishedAt": finished_at_ms}
    if not isinstance(started, int) or not instance_type:
        return out
    seconds = max(0.0, (finished_at_ms - started) / 1000.0)
    spot = bool(meta.get("spot"))
    out["actualComputeSeconds"] = int(round(seconds))
    out["actualComputeUsd"] = round(actual_compute_cost(seconds, str(instance_type), spot), 4)
    out["finalInstanceType"] = instance_type
    return out


class DynamoStore:
    def __init__(self, table_name: str, region: str):
        self._table = boto3.resource("dynamodb", region_name=region).Table(table_name)

    def update_shoot(self, shoot_id: str, **attrs: Any) -> None:
        names: dict[str, str] = {}
        values: dict[str, Any] = {":u": int(time.time())}
        parts = ["updatedAt = :u"]
        for i, (k, v) in enumerate(attrs.items()):
            nk, vk = f"#k{i}", f":v{i}"
            names[nk] = k
            values[vk] = v
            parts.append(f"{nk} = {vk}")
        self._table.update_item(
            Key={"pk": f"SHOOT#{shoot_id}", "sk": "META"},
            UpdateExpression="SET " + ", ".join(parts),
            ExpressionAttributeNames=names,
            ExpressionAttributeValues=values,
        )

    def update_image(self, shoot_id: str, sha256: str, **attrs: Any) -> None:
        names: dict[str, str] = {}
        values: dict[str, Any] = {":u": int(time.time())}
        parts = ["updatedAt = :u"]
        for i, (k, v) in enumerate(attrs.items()):
            nk, vk = f"#k{i}", f":v{i}"
            names[nk] = k
            values[vk] = v
            parts.append(f"{nk} = {vk}")
        self._table.update_item(
            Key={"pk": f"SHOOT#{shoot_id}", "sk": f"FILE#{sha256.lower()}"},
            UpdateExpression="SET " + ", ".join(parts),
            ExpressionAttributeNames=names,
            ExpressionAttributeValues=values,
        )

    def get_image(self, shoot_id: str, sha256: str) -> dict[str, Any] | None:
        r = self._table.get_item(Key={"pk": f"SHOOT#{shoot_id}", "sk": f"FILE#{sha256.lower()}"})
        return r.get("Item")

    def get_shoot(self, shoot_id: str) -> dict[str, Any] | None:
        r = self._table.get_item(Key={"pk": f"SHOOT#{shoot_id}", "sk": "META"})
        return r.get("Item")

    def finish_run(self, shoot_id: str, **attrs: Any) -> None:
        """Terminal shoot update: clears ephemeral EC2 fields from META."""
        meta = self.get_shoot(shoot_id) or {}
        finished_at = int(attrs.get("workerFinishedAt") or int(time.time() * 1000))
        attrs = {**attrs, **compute_finish_snapshot(meta, finished_at)}
        names: dict[str, str] = {}
        values: dict[str, Any] = {":u": int(time.time())}
        parts = ["updatedAt = :u"]
        for i, (k, v) in enumerate(attrs.items()):
            nk, vk = f"#k{i}", f":v{i}"
            names[nk] = k
            values[vk] = v
            parts.append(f"{nk} = {vk}")
        remove = (
            " REMOVE instanceId, instanceType, spot, workerStartedAt, launchLockUntil,"
            " deadlineAt, maxRuntimeMinutes"
        )
        self._table.update_item(
            Key={"pk": f"SHOOT#{shoot_id}", "sk": "META"},
            UpdateExpression="SET " + ", ".join(parts) + remove,
            ExpressionAttributeNames=names,
            ExpressionAttributeValues=values,
        )
