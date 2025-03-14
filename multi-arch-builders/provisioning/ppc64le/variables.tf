
variable "ibmcloud_api_key" {
    description = "Denotes the IBM Cloud API key to use"
    default = ""
}

variable "ibmcloud_region" {
    description = "Denotes which IBM Cloud region to connect to"
    default     = "us-south"
}

#INSERTED FOR MULTI-ZONE REGION SUCH AS FRANKFURT

variable "ibmcloud_zone" {
    description = "Denotes which IBM Cloud zone to connect to - .i.e: eu-de-1 eu-de-2  us-south etc."
    default     = "us-south"
}

# Got the ID from `ibmcloud resource service-instances --long field` command, refer GUID for the instance
variable "power_instance_id" {
    description = "Power Virtual Server instance ID associated with your IBM Cloud account (note that this is NOT the API key)"
    default = "556eb201-32bf-4ae2-8ab5-dfd7bbe97789"
}


# The PowerVs cost are high, check the price before adding
# more processors and memory. This number may change
# due the PowerVs availability.

variable "memory" {
    description = "Amount of memory (GB) to be allocated to the VM"
    default     = "50"
}

variable "processors" {
    description = "Number of virtual processors to allocate to the VM"
    default     = "15"
}

# The s922 model is the cheapest model
variable "system_type" {
    description = "Type of system on which the VM should be created - s922/e880/e980"
    default     = "s922"
}

variable "proc_type" {
    description = "Processor type for the LPAR - shared/dedicated"
    default     = "capped"
}

variable "ssh_key_name" {
    description = "SSH key name in IBM Cloud to be used for SSH logins"
    default     = ""
}

variable "shareable" {
    description = "Should the data volume be shared or not - true/false"
    default     = "true"
}

# TODO: We need to add the network creation via tofu for fcos
# This config is for rhcos only
variable "network" {
    description = "List of networks that should be attached to the VM - Create this network before running terraform"
    default     = "redhat-internal-rhcos" 
}


variable "image_name" {
    description = "Name of the image from which the VM should be deployed - IBM image name"
    default     = "fedora-coreos-39-2023110110"
}

variable "replication_policy" {
    description = "Replication policy of the VM"
    default     = "none"
}

variable "replication_scheme" {
    description = "Replication scheme for the VM"
    default     = "suffix"
}

variable "replicants" {
    description = "Number of VM instances to deploy"
    default     = "1"
}
