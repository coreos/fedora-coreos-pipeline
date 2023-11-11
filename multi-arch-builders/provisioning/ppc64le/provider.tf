terraform {
  required_providers {
    ct = {
      source  = "poseidon/ct"
      version = "0.13.0"
    }
    ibm = {
      source = "IBM-Cloud/ibm"
      version = ">= 1.12.0"
    }
  }
}

provider "ibm" {
    ibmcloud_api_key = var.ibmcloud_api_key
    region    = "us-south"
    zone      = var.ibmcloud_zone
}
