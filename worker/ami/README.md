# Worker runtime (stock AL2023 — no custom AMI)

Production workers boot from the **latest Amazon Linux 2023** AMI. Finalize Lambda user-data downloads:

- `s3://<bucket>/releases/photopipeline-worker.tar.gz` (Python worker; build with `scripts/build-worker-bundle.sh`)
- `s3://<bucket>/releases/lightcraft-cli` (prebuilt binary + SHA256)

Both checksums are required on the finalize Lambda (`WORKER_BUNDLE_SHA256`, `LIGHTCRAFT_BINARY_SHA256`).

## Spot service-linked role (one-time)

The finalize role can call `iam:CreateServiceLinkedRole` for `spot.amazonaws.com`. Alternatively, an account admin runs once:

```bash
aws iam create-service-linked-role --aws-service-name spot.amazonaws.com
```

## Packer / bootstrap (dev only)

`bootstrap.sh` still builds LightCraft from source for local AMI experiments; **do not** rely on a baked AMI in production.

## Quick verify (CI / local)

```bash
bash -n worker/ami/bootstrap.sh
docker build -f worker/ami/Dockerfile.verify .
```

Set `SKIP_LIGHTCRAFT_BUILD=0` in the Dockerfile or on an EC2 builder instance for a full compile (~2–3 min on c7i.2xlarge).

## Packer (optional)

```bash
packer init worker/ami/packer.pkr.hcl
packer build -var 'source_worker_dir=worker' worker/ami/packer.pkr.hcl
```

Register the resulting AMI ID in CDK deploy parameter / `WORKER_AMI_ID` environment for the finalize Lambda.

## Runtime

Finalize Lambda user-data expects:

- `LIGHTCRAFT_BIN=/opt/photopipeline/lightcraft/lightcraft-cli`
- Worker code at `/opt/photopipeline/worker`

Update `worker/worker/config.py` default or EC2 user-data `export LIGHTCRAFT_BIN=...` accordingly.
