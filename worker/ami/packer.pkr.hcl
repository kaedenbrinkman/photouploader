# Optional: packer build -var 'source_worker_dir=../../worker' worker/ami/packer.pkr.hcl
packer {
  required_plugins {
    amazon = {
      version = ">= 1.2.8"
      source  = "github.com/hashicorp/amazon"
    }
  }
}

variable "region" {
  type    = string
  default = "us-west-2"
}

variable "source_worker_dir" {
  type = string
}

source "amazon-ebs" "worker" {
  ami_name      = "photopipeline-worker-{{timestamp}}"
  instance_type = "c7i.2xlarge"
  region        = var.region
  source_ami_filter {
    filters = {
      name                = "al2023-ami-*-x86_64"
      root-device-type    = "ebs"
      virtualization-type = "hvm"
    }
    owners      = ["amazon"]
    most_recent = true
  }
  ssh_username = "ec2-user"
}

build {
  sources = ["source.amazon-ebs.worker"]

  provisioner "file" {
    source      = "${var.source_worker_dir}/"
    destination = "/tmp/photopipeline-worker"
  }

  provisioner "file" {
    source      = "${path.root}/bootstrap.sh"
    destination = "/tmp/bootstrap.sh"
  }

  provisioner "shell" {
    inline = [
      "chmod +x /tmp/bootstrap.sh",
      "sudo LIGHTCRAFT_COMMIT=31a677fe3dbf4bc1042f8f6346e83b0afd1b04e8 SKIP_LIGHTCRAFT_BUILD=0 /tmp/bootstrap.sh",
    ]
  }
}
