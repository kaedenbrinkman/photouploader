"""Shared manifest, dedupe, and state-machine logic for backend and worker."""

from photopipeline.identity import FileIdentity, file_identity_key
from photopipeline.manifest import ManifestFile, ShootManifest, validate_manifest
from photopipeline.state import (
    ImageState,
    ShootState,
    can_transition_image,
    can_transition_shoot,
    next_shoot_state_on_finalize,
)

__all__ = [
    "FileIdentity",
    "file_identity_key",
    "ManifestFile",
    "ShootManifest",
    "validate_manifest",
    "ImageState",
    "ShootState",
    "can_transition_image",
    "can_transition_shoot",
    "next_shoot_state_on_finalize",
]
