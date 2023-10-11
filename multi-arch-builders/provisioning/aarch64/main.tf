terraform {
  required_providers {
    ct = {
      source  = "poseidon/ct"
      version = "0.13.0"
    }
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    http = {
      source  = "hashicorp/http"
      version = "2.1.0"
    }
  }
}

provider "aws" {}
provider "ct" {}
provider "http" {}

# Get ignition created for the multiarch builder
resource "null_resource" "butane" {
  provisioner "local-exec" {
    command = "bash -x ./butane.sh" 
  }
}

data "aws_region" "aws_region" {}

# Gather information about the AWS image for the current region
data "http" "stream_metadata" {
  url = "https://builds.coreos.fedoraproject.org/streams/stable.json"

  request_headers = {
    Accept = "application/json"
  }
}
# Lookup the aarch64 AWS image for the current AWS region
locals {
  ami = lookup(jsondecode(data.http.stream_metadata.body).architectures.aarch64.images.aws.regions, data.aws_region.aws_region.name).image
}

resource "aws_instance" "coreos-multiarch-builder-aarch64" {
  tags = {
    Name = "coreos-aarch64-builder-${formatdate("YYYYMMDD", timestamp())}"
  }
  ami           = local.ami 
  user_data     = file("coreos-aarch64-builder.ign")
  instance_type = "m6g.metal"
  vpc_security_group_ids = [aws_security_group.sg.id] 
  subnet_id              = var.aws_subnet_internal
  root_block_device {
      volume_size = "200"
      volume_type = "gp3"
  }
}

output "instance_ip_addr" {
  value = aws_instance.coreos-multiarch-builder-aarch64.private_ip
} 
