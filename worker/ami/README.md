# Worker AMI bootstrap

Builds `/opt/photopipeline/lightcraft/lightcraft-cli` from pinned LightCraft commit **31a677fe** (Rust **≥ 1.90**) and installs the Python worker.

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
