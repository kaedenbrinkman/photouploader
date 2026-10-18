# Feasibility report (summary)

Full investigation: see project upload / original `REPORT.md`. Key points:

- LightCraft v0.9.1 runs headless on Linux; use `develop.auto`, **as-shot WB**, `longEdge=0` for full-res JPEG.
- Reject CR3s where `library.importPreview` would set `previewOnly=Some` (silent embedded-JPEG fallback).
- Google Photos: `appendonly` + `readonly.appcreateddata`; app-created albums only; batchCreate ≤50; 429 → wait ≥30s.
- Testing-mode refresh tokens expire after 7 days — production OAuth verification or weekly re-auth.
- Android MTP: adapt canon-mtp-direct; dedupe must not rely on filename alone.

State machine: shoot phases `IMPORTING → UPLOADING → … → DONE | FAILED`; per-image states through `GP_CREATED`.
