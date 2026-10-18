"""Shoot cost and duration estimates (mirrors config/pricing.json)."""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass
class CostEstimate:
    image_count: int
    total_bytes_raw: int
    instance_type: str
    use_spot: bool
    processing_minutes: float
    compute_usd: float
    s3_standard_month_usd: float
    s3_deep_archive_month_usd: float
    requests_usd: float
    total_shoot_usd: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "imageCount": self.image_count,
            "totalBytesRaw": self.total_bytes_raw,
            "instanceType": self.instance_type,
            "spot": self.use_spot,
            "processingMinutes": round(self.processing_minutes, 1),
            "computeUsd": round(self.compute_usd, 4),
            "s3StandardMonthUsd": round(self.s3_standard_month_usd, 4),
            "s3DeepArchiveMonthUsd": round(self.s3_deep_archive_month_usd, 4),
            "requestsUsd": round(self.requests_usd, 4),
            "totalShootUsd": round(self.total_shoot_usd, 4),
        }


def load_pricing_table(path: Path | None = None) -> dict[str, Any]:
    p = path or Path(__file__).resolve().parents[2] / "config" / "pricing.json"
    return json.loads(p.read_text())


def pick_instance(pricing: dict[str, Any], image_count: int) -> tuple[str, bool]:
    ec2 = pricing["ec2"]
    for it in ec2["instanceTypeOrder"]:
        par = ec2["parallelism"][it]
        # rough wall time with chosen parallelism
        secs = ec2["bootOverheadMinutes"] * 60 + (
            image_count * ec2["secondsPerImageAtReferenceParallelism"] / par
        )
        if it == "c7i.2xlarge" or image_count >= 50:
            return it, bool(ec2.get("preferSpot", True))
    return ec2["instanceTypeOrder"][0], bool(ec2.get("preferSpot", True))


def estimate_shoot(
    image_count: int,
    total_bytes_raw: int,
    pricing: dict[str, Any] | None = None,
) -> CostEstimate:
    if image_count <= 0:
        raise ValueError("image_count must be positive")
    p = pricing or load_pricing_table()
    ec2 = p["ec2"]
    s3 = p["s3"]
    instance_type, use_spot = pick_instance(p, image_count)
    par = ec2["parallelism"][instance_type]
    processing_seconds = ec2["bootOverheadMinutes"] * 60 + (
        image_count * ec2["secondsPerImageAtReferenceParallelism"] / par
    )
    processing_minutes = processing_seconds / 60
    rate_key = "spotUsdPerHour" if use_spot else "onDemandUsdPerHour"
    hourly = ec2[rate_key][instance_type]
    compute_usd = hourly * (processing_seconds / 3600)
    gb = total_bytes_raw / (1024**3)
    s3_std = gb * s3["standardUsdPerGbMonth"]
    s3_da = gb * s3["deepArchiveUsdPerGbMonth"]
    puts = image_count * 3  # raw + jpeg + metadata rough
    requests_usd = (puts / 1000) * s3["putPer1000"] + p["lambdaAndApi"]["estimatePerShootUsd"]
    total = compute_usd + s3_std + requests_usd
    return CostEstimate(
        image_count=image_count,
        total_bytes_raw=total_bytes_raw,
        instance_type=instance_type,
        use_spot=use_spot,
        processing_minutes=processing_minutes,
        compute_usd=compute_usd,
        s3_standard_month_usd=s3_std,
        s3_deep_archive_month_usd=s3_da,
        requests_usd=requests_usd,
        total_shoot_usd=total,
    )


def actual_compute_cost(compute_seconds: float, instance_type: str, spot: bool, pricing: dict[str, Any] | None = None) -> float:
    p = pricing or load_pricing_table()
    rate_key = "spotUsdPerHour" if spot else "onDemandUsdPerHour"
    hourly = p["ec2"][rate_key][instance_type]
    return hourly * (compute_seconds / 3600)
