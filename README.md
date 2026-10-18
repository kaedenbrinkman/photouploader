# Photo Pipeline

Canon EOS R8 → Pixel 9 Pro → S3 → ephemeral EC2 (LightCraft) → Google Photos.

Personal, multi-user (owner + friends/family), **near-zero idle AWS cost**. Each user links their own Google account; RAW originals land in encrypted S3; one spot/on-demand **c7i** worker per completed shoot processes CR3s headlessly and publishes JPEGs to an **app-created** Google Photos album.

## Architecture

```mermaid
flowchart LR
  subgraph phone [Pixel app]
    MTP[MTP import + SHA-256]
    WM[WorkManager S3 upload]
  end
  subgraph aws [AWS pay-per-use]
    API[HTTP API + Lambda]
    DDB[(DynamoDB on-demand)]
    S3[(S3 SSE-S3)]
    EC2[Ephemeral c7i worker]
  end
  GP[Google Photos API]
  MTP --> WM --> S3
  WM --> API
  API --> DDB
  API --> EC2
  EC2 --> S3
  EC2 --> GP
  phone --> API
```

**Why AWS CDK (TypeScript)?** The stack is AWS-native (HTTP API, Lambda, DynamoDB, S3, EventBridge, EC2 run-instances). CDK gives typed IAM, `cdk synth` validation without deploying, and keeps Lambda + infra in one repo. Terraform would work; CDK is less boilerplate for Node Lambdas and bundling.

### Idle cost (excluding stored photos)

| Resource | Idle charge |
|----------|-------------|
| HTTP API | $0 (pay per request) |
| Lambda | $0 |
| DynamoDB on-demand | $0 (no storage → no cost; pennies at this scale) |
| S3 empty bucket | ~$0 |
| SSM SecureString | ~$0 (Standard parameters; no per-secret $0.40/mo) |
| Session JWT authorizer (Lambda) | $0 idle |
| EC2 | $0 (only when processing) |
| EventBridge hourly watchdog | ~$0.001/mo |
| CloudWatch Logs | Cents if unused; **7-day retention** on Lambdas |

**Expected idle total: ~$0–0.05/mo** until you store RAWs/JPEGs or run shoots. A 500-image shoot might land around **~$1** all-in (see `config/pricing.json` and `/shoots/estimate`).

**Deliberately omitted:** NAT gateway, ALB, always-on EC2, interface VPC endpoints, custom KMS ($1/mo/key), Secrets Manager per-user secrets ($0.40/mo each). Google refresh tokens live in **SSM SecureString** (default AWS-managed key).

## Repo layout

| Path | Purpose |
|------|---------|
| `android/` | Kotlin / Compose / Room / WorkManager app |
| `infra/` | AWS CDK stack + Lambda handlers |
| `worker/` | EC2 worker (LightCraft + GPhotos) |
| `gphotos/` | Google Photos uploader (from feasibility study) |
| `lib/photopipeline/` | Shared manifest, state machine, pricing |
| `config/pricing.json` | Configurable pricing table for estimates |

## Worker AMI

Reproducible bootstrap: `worker/ami/bootstrap.sh` (LightCraft **31a677fe**, Rust **≥ 1.90**). CI runs `bash -n` and a Docker verify build (`worker/ami/Dockerfile.verify`) with `SKIP_LIGHTCRAFT_BUILD=1`. Optional Packer template: `worker/ami/packer.pkr.hcl`.

## End-to-end upload path

1. App imports via **CanonMtpRepository** (`MtpDevice`, partial reads for resume; camera originals untouched).
2. **UploadWorker** multipart uploads with **ETags in Room** (`uploadPartsJson`), `list_parts` recovery, **`complete_multipart`**.
3. App uploads **`manifest.json`** (presigned PUT) then **`finalize`**; Lambda validates manifest identity + S3 sizes.

## Deploy (not run in CI — no real credentials)

1. **Google Cloud:** Follow **`gphotos/GOOGLE_OAUTH_ANDROID.md`** (Web + Android OAuth clients, redirect URI, SHA-1) and `gphotos/SETUP.md` for Photos API consent/scopes.
2. **AWS:** `cd infra && npm install && npx cdk bootstrap && npx cdk deploy`
   - **`WorkerVpcId`** and **`WorkerPublicSubnetId`** are **required** CloudFormation parameters (no in-stack default). Use your account’s default VPC and a **public** subnet (worker needs outbound internet; worker security group has no inbound rules). Example discovery:
     ```bash
     VPC=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
     SUBNET=$(aws ec2 describe-subnets --filters Name=vpc-id,Values="$VPC" Name=map-public-ip-on-launch,Values=true \
       --query 'Subnets[0].SubnetId' --output text)
     npx cdk deploy --parameters WorkerVpcId="$VPC" --parameters WorkerPublicSubnetId="$SUBNET"
     ```
   - SSM SecureString params (manual; see stack outputs `GoogleOAuthClientSetup` and `SessionJwtSecretSetup`):
     - `/photopipeline/google-oauth-client` — **Web application** `client_secret.json`
     - `/photopipeline/session-jwt-secret` — random signing key for API session JWTs
   - Android: defaults to API `https://3jamg4s1j9.execute-api.us-west-2.amazonaws.com`; paste **Web OAuth client ID** in Settings, tap **Connect with Google**.
3. **Worker AMI:** Bake Amazon Linux 2023 with `lightcraft-cli` (pin commit from feasibility report), Python worker under `/opt/photopipeline/worker`, then point user-data at `python3 -m worker.main`.

## API highlights

- `POST /shoots/estimate` — cost & duration from `config/pricing.json` (auth required).
- `GET /pricing` — public pricing table JSON.
- `POST /shoots`, `POST /shoots/{id}/presign` — multipart presigns.
- `POST /shoots/{id}/finalize` — verify manifest, launch EC2 (spot → on-demand fallback).
- `GET /shoots/{id}/status` — shoot phase, per-file states, actual compute cost after done.
- `POST /auth/google` — exchange Android **server auth code** (GIS offline access); returns session JWT; stores refresh token per Google `sub` in SSM.

## Android app

- **Connect with Google** (PKCE via Custom Tabs); backend session JWT for API calls; Google Photos refresh token on server.
- USB host / MTP (Canon vendor `0x04A9`); `MockMtpRepository` for tests, `CanonMtpRepository` stub for hardware.
- Dedupe key: storage + handle + size + capture time + SHA-256.
- WorkManager foreground uploads with per-file progress, retry, Room persistence.
- **Before submit:** cost estimate from backend; **after processing:** actual compute time & cost from status poll (survives app restart).

## Tests & CI

```bash
cd lib && pytest
cd worker && PYTHONPATH=. pytest
PYTHONPATH=lib pytest backend/tests
cd infra && npm test && npx cdk synth
cd android && ./gradlew assembleDebug test
```

GitHub Actions: `.github/workflows/ci.yml`.

## Verified vs untested

| Area | Status |
|------|--------|
| LightCraft on Linux, R8 corpus tests, CLI flags | **Verified** in feasibility study (`docs/REPORT.md` — copy from upload) |
| Google Photos API behavior | **Documented**, uploader not live-tested |
| This repo: CDK synth, unit/moto tests, Android build | **Verified** in CI |
| Real Canon R8 + Pixel 9 Pro USB | **Not tested** |
| Real AWS deploy, EC2 AMI, end-to-end shoot | **Not tested** |
| Real Google account album creation | **Not tested** |

## Cost estimate endpoint

Example body:

```json
{ "imageCount": 120, "totalBytesRaw": 12000000000 }
```

Returns compute (spot c7i), processing minutes, S3 Standard first-month storage, and total shoot USD — same logic in `lib/photopipeline/pricing.py` and `infra/lambda/pricing.ts`.

## Feasibility reference

See uploaded feasibility report for LightCraft (`longEdge=0`, as-shot WB, `previewOnly` check), Google Photos limits (batch 50, 429 backoff, Testing-mode 7-day tokens), canon-mtp-direct notes, and the shoot/image state machine.
