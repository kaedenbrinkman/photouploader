"""DynamoDB shoot/image status updates."""

from __future__ import annotations

import time
from typing import Any

import boto3


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
