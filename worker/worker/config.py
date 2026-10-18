import os
from dataclasses import dataclass


@dataclass
class WorkerConfig:
    shoot_id: str
    user_id: str
    bucket: str
    settings_hash: str
    album_title: str
    lightcraft_bin: str
    work_dir: str
    max_parallel_renders: int
    gphotos_token_param: str
    dynamodb_table: str
    region: str

    @classmethod
    def from_env(cls) -> "WorkerConfig":
        return cls(
            shoot_id=os.environ["SHOOT_ID"],
            user_id=os.environ["USER_ID"],
            bucket=os.environ["BUCKET_NAME"],
            settings_hash=os.environ.get("SETTINGS_HASH", "develop.auto-longEdge0-q90"),
            album_title=os.environ.get("ALBUM_TITLE", os.environ["SHOOT_ID"]),
            lightcraft_bin=os.environ.get("LIGHTCRAFT_BIN", "/opt/lightcraft/lightcraft-cli"),
            work_dir=os.environ.get("WORK_DIR", "/var/lib/photopipeline"),
            max_parallel_renders=int(os.environ.get("MAX_PARALLEL_RENDERS", "4")),
            gphotos_token_param=os.environ["GPHOTOS_TOKEN_PARAM"],
            dynamodb_table=os.environ["DYNAMODB_TABLE"],
            region=os.environ.get("AWS_REGION", "us-west-2"),
        )
