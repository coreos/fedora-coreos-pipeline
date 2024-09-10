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


# Variables used for splunk deployment, which is only
# for RHCOS builders. Define them in the environment with:
# export TF_VAR_splunk_hostname=...
# export TF_VAR_splunk_sidecar_repo=...
# export TF_VAR_itpaas_splunk_repo=...
variable "splunk_hostname" {
 type    = string
 default = ""
}
variable "splunk_sidecar_repo" {
 type    = string
 default = ""
}
variable "itpaas_splunk_repo" {
 type    = string
 default = ""
}
# Check that if we are deploying a RHCOS builder the splunk
# variables have been defined.
check "health_check_rhcos_splunk_vars" {
  assert {
    condition = !(var.distro == "rhcos" && anytrue([
                        var.splunk_hostname == "",
                        var.splunk_sidecar_repo == "",
                        var.itpaas_splunk_repo == ""
                    ]))
    error_message = "Must define splunk env vars for RCHOS builders"
  }
}


locals {
    fcos_snippets = [
      file("../../coreos-aarch64-builder.bu"),
    ]
    rhcos_snippets = [
      file("../../coreos-aarch64-builder.bu"),
      templatefile("../../builder-splunk.bu", {
        SPLUNK_HOSTNAME = var.splunk_hostname
        SPLUNK_SIDECAR_REPO = var.splunk_sidecar_repo
        ITPAAS_SPLUNK_REPO = var.itpaas_splunk_repo
      })
    ]
}
data "ct_config" "butane" {
  strict = true
  content = file("../../builder-common.bu")
  snippets = var.distro == "rhcos" ? local.rhcos_snippets : local.fcos_snippets
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
  aws_vpc_id = var.distro == "rhcos" ? var.rhcos_aws_vpc_prod : aws_vpc.vpc[0].id
  aws_subnet_id = var.distro == "rhcos" ? var.rhcos_aws_subnet_internal : aws_subnet.private_subnets[0].id
}


resource "aws_instance" "coreos-aarch64-builder" {
  tags = {
    Name = "${var.project}-${formatdate("YYYYMMDD", timestamp())}"
  }
  ami           = local.ami
  user_data     = data.ct_config.butane.rendered
  instance_type = "m6g.metal"
  vpc_security_group_ids = [aws_security_group.sg.id]
  subnet_id              = local.aws_subnet_id
  root_block_device {
      volume_size = "400"
      volume_type = "gp3"
  }
  associate_public_ip_address = var.distro == "fcos" ? "true" : "false"
}

output "instance_ip_addr" {
  value = var.distro == "rhcos" ? aws_instance.coreos-aarch64-builder.private_ip : aws_instance.coreos-aarch64-builder.public_ip
}
