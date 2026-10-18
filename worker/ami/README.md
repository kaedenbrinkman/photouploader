# Worker runtime (stock AL2023 — no custom AMI)

Production workers boot from the **latest Amazon Linux 2023** AMI. Finalize Lambda user-data downloads:

- `s3://<bucket>/releases/photopipeline-worker.tar.gz` (Python worker; build with `scripts/build-worker-bundle.sh`)
- `s3://<bucket>/releases/lightcraft-cli` (prebuilt binary)

Before launch, finalize **HEAD**s both objects, requires non-zero size, and verifies **ChecksumSHA256** or object metadata `sha256` against SSM (see below).

## Worker SHA256 via SSM (no Lambda redeploy on update)

Store expected hex SHA256 in **SSM String parameters** (read at finalize runtime):

| Parameter | Purpose |
|-----------|---------|
| `/photopipeline/worker-bundle-sha256` | `releases/photopipeline-worker.tar.gz` |
| `/photopipeline/lightcraft-sha256` | `releases/lightcraft-cli` |

Example (current release):

```bash
aws ssm put-parameter --name /photopipeline/worker-bundle-sha256 \
  --type String --value b061a429a89d5cafaf7a504906bbfbf36a5f7ae28f7ce21a8854cdc608fee738 --overwrite
aws ssm put-parameter --name /photopipeline/lightcraft-sha256 \
  --type String --value 39ed19f86cf2a6c7b18c53add2a32cbd0e03bfb89f34bc4489336c8d94484dfd --overwrite
```

Upload objects with matching checksum metadata (either form works):

```bash
aws s3 cp dist/photopipeline-worker.tar.gz s3://BUCKET/releases/photopipeline-worker.tar.gz \
  --metadata sha256=b061a429a89d5cafaf7a504906bbfbf36a5f7ae28f7ce21a8854cdc608fee738
aws s3 cp lightcraft-cli s3://BUCKET/releases/lightcraft-cli \
  --metadata sha256=39ed19f86cf2a6c7b18c53add2a32cbd0e03bfb89f34bc4489336c8d94484dfd
```

Or use `aws s3api put-object` with `ChecksumSHA256` (base64 of raw SHA256 bytes).

If artifacts are missing or checksums disagree, finalize returns **503** `worker_artifacts_missing` and leaves the shoot **READY_TO_PROCESS**.

## Spot service-linked role (one-time)

The finalize role can call `iam:CreateServiceLinkedRole` for `spot.amazonaws.com`. Alternatively, an account admin runs once:

```bash
aws iam create-service-linked-role --aws-service-name spot.amazonaws.com
```

Worker instances use `InstanceInitiatedShutdownBehavior=terminate`; bootstrap failures call `shutdown -h now` after writing `lastError` to DynamoDB.

## Packer / bootstrap (dev only)

`bootstrap.sh` still builds LightCraft from source for local AMI experiments; **do not** rely on a baked AMI in production.

## Quick verify (CI / local)

```bash
bash -n worker/ami/bootstrap.sh
bash -n worker/ami/ec2-install-from-s3.sh
docker build -f worker/ami/Dockerfile.verify .
chmod +x scripts/build-worker-bundle.sh && scripts/build-worker-bundle.sh
```

Set `SKIP_LIGHTCRAFT_BUILD=0` in the Dockerfile or on an EC2 builder instance for a full compile (~2–3 min on c7i.2xlarge).

## Runtime paths

- `LIGHTCRAFT_BIN=/opt/photopipeline/lightcraft/lightcraft-cli`
- Worker code at `/opt/photopipeline/photopipeline-worker` (from tarball)
