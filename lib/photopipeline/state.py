"""Shoot and image state machine (from feasibility report §6)."""

from __future__ import annotations

from enum import Enum


class ShootState(str, Enum):
    IMPORTING = "IMPORTING"
    UPLOADING = "UPLOADING"
    MANIFEST_SUBMITTED = "MANIFEST_SUBMITTED"
    VERIFIED = "VERIFIED"
    QUEUED = "QUEUED"
    PROCESSING = "PROCESSING"
    PUBLISHING = "PUBLISHING"
    DONE = "DONE"
    PARTIAL = "PARTIAL"
    FAILED = "FAILED"
    RERUN_REQUESTED = "RERUN_REQUESTED"
    NEEDS_REAUTH = "NEEDS_REAUTH"
    PUBLISH_DEFERRED = "PUBLISH_DEFERRED"


class ImageState(str, Enum):
    ON_PHONE = "ON_PHONE"
    UPLOADED = "UPLOADED"
    RENDERED = "RENDERED"
    GP_UPLOADED = "GP_UPLOADED"
    GP_CREATED = "GP_CREATED"
    FAILED_RENDER = "FAILED_RENDER"
    PREVIEW_ONLY = "PREVIEW_ONLY"
    FAILED_GP = "FAILED_GP"


SHOOT_TRANSITIONS: dict[ShootState, set[ShootState]] = {
    ShootState.IMPORTING: {ShootState.UPLOADING},
    ShootState.UPLOADING: {ShootState.MANIFEST_SUBMITTED, ShootState.UPLOADING},
    ShootState.MANIFEST_SUBMITTED: {ShootState.VERIFIED, ShootState.UPLOADING},
    ShootState.VERIFIED: {ShootState.QUEUED},
    ShootState.QUEUED: {ShootState.PROCESSING, ShootState.FAILED},
    ShootState.PROCESSING: {ShootState.PUBLISHING, ShootState.PARTIAL, ShootState.FAILED},
    ShootState.PUBLISHING: {
        ShootState.DONE,
        ShootState.PARTIAL,
        ShootState.FAILED,
        ShootState.NEEDS_REAUTH,
        ShootState.PUBLISH_DEFERRED,
    },
    ShootState.RERUN_REQUESTED: {ShootState.QUEUED},
    ShootState.PUBLISH_DEFERRED: {ShootState.PUBLISHING},
    ShootState.NEEDS_REAUTH: {ShootState.PUBLISHING},
}


IMAGE_TRANSITIONS: dict[ImageState, set[ImageState]] = {
    ImageState.ON_PHONE: {ImageState.UPLOADED},
    ImageState.UPLOADED: {ImageState.RENDERED, ImageState.PREVIEW_ONLY, ImageState.FAILED_RENDER},
    ImageState.RENDERED: {ImageState.GP_UPLOADED, ImageState.FAILED_GP},
    ImageState.GP_UPLOADED: {ImageState.GP_CREATED, ImageState.FAILED_GP},
    ImageState.FAILED_RENDER: {ImageState.RENDERED, ImageState.PREVIEW_ONLY},
    ImageState.FAILED_GP: {ImageState.GP_UPLOADED, ImageState.GP_CREATED},
}


def can_transition_shoot(current: ShootState, target: ShootState) -> bool:
    if current == target:
        return True
    return target in SHOOT_TRANSITIONS.get(current, set())


def can_transition_image(current: ImageState, target: ImageState) -> bool:
    if current == target:
        return True
    return target in IMAGE_TRANSITIONS.get(current, set())


def next_shoot_state_on_finalize(verified: bool) -> ShootState:
    return ShootState.VERIFIED if verified else ShootState.UPLOADING


def output_key(sha256: str, settings_hash: str) -> str:
    return f"{sha256.lower()}:{settings_hash}"
