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

variable "project" {
 type    = string
 default = "coreos-aarch64-builder"
}

# Which distro are we deploying a builder for? Override the
# default by setting the env var: TF_VAR_distro=rhcos
variable "distro" {
 type    = string
 default = "fcos"
}
check "health_check_distro" {
  assert {
    condition = anytrue([
                    var.distro == "fcos",
                    var.distro == "rhcos"
                    ])
    error_message = "Distro must be 'fcos' or 'rhcos'"
  }
}

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

variable "rhcos_aws_vpc_prod" {
  description = "RHCOS Prod US East 2"
  default = "vpc-0e33d95334e362c7e"
}
variable "rhcos_aws_subnet_internal" {
  description = "RHCOS Prod US East 2 subnet"
  default = "subnet-02014b5e587d01fd2"
}
# If we are RHCOS we'll be using an already existing VPC/subnet rather
# than the newly created one.
locals {
  aws_vpc_id = var.distro == "rhcos" ? var.rhcos_aws_vpc_prod : aws_vpc.vpc.id
  aws_subnet_id = var.distro == "rhcos" ? var.rhcos_aws_subnet_internal : aws_subnet.private_subnets[0].id
}


resource "aws_instance" "coreos-aarch64-builder" {
  tags = {
    Name = "${var.project}-${formatdate("YYYYMMDD", timestamp())}"
  }
  ami           = local.ami 
  user_data     = file("coreos-aarch64-builder.ign")
  instance_type = "m6g.metal"
  vpc_security_group_ids = [aws_security_group.sg.id] 
  subnet_id              = local.aws_subnet_id
  root_block_device {
      volume_size = "200"
      volume_type = "gp3"
  }
}

output "instance_ip_addr" {
  value = aws_instance.coreos-aarch64-builder.private_ip
} 
