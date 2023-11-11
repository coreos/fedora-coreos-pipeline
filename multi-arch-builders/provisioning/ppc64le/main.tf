data "ibm_pi_network" "network" {
    pi_network_name      = var.network
    pi_cloud_instance_id = var.power_instance_id
}

data "ibm_pi_image" "power_images" {
    pi_image_name        = var.image_name
    pi_cloud_instance_id = var.power_instance_id
}

provider "ct" {} 

variable "project" {
 type    = string
 default = "coreos-ppc64le-builder"
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
      file("../../coreos-ppc64le-builder.bu"),
    ]
    rhcos_snippets = [
      file("../../coreos-ppc64le-builder.bu"),
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



resource "ibm_pi_instance" "pvminstance" {
    pi_memory             = var.memory
    pi_processors         = var.processors
    pi_instance_name      = "${var.project}-${formatdate("YYYYMMDD", timestamp())}"
    pi_proc_type          = var.proc_type
    pi_image_id           = data.ibm_pi_image.power_images.id
    pi_network {
      network_id = data.ibm_pi_network.network.id
    }
    pi_key_pair_name      = var.ssh_key_name
    pi_sys_type           = var.system_type
    pi_cloud_instance_id  = var.power_instance_id
    pi_user_data          = base64encode(data.ct_config.butane.rendered)

}
