# OpenTofu

 OpenTofu, a Terraform fork, is an open-source infrastructure as code (IaC) tool
 lets you define both cloud and on-prem resources in human-readable configuration files
 that you can version, reuse, and share.

 To proceed with the next steps, ensure that 'tofu' is installed on your system.
 See: https://github.com/opentofu/opentofu/releases

## Before starting

### PowerVS credentials

 - Ensure that you have access to our account.
 - Verify that the Fedora CoreOS image has been uploaded to the designated bucket.
   - TODO: Add bucket creation and image upload to tofu
   - See documetation in how to upload the image manually:
       https://cloud.ibm.com/docs/power-iaas?topic=power-iaas-deploy-custom-image
### PowerVs Issues

 - PowerVS seems to encounter a problem in creating the default local IP with the default route,
resulting in issues to ssh to the server post-boot.
To mitigate this, we've incorporated networking configurations into the Ignition file. However,
we still with one issue during the Splunk Butane configuration, where the CA certification couldn't be
downloaded during provisioning. If you encounter this issue, comment out the Red Hat CA download step
and perform it manually on the machine after provisioning. 

 - Additionally, it's important to note that PowerVS lacks the user data field in the web interface for providing
the Ignition config.

### TF vars via environment variables

If you'd like to override the target distro (defaults to `fcos`) you
can:

```
export TF_VAR_distro=rhcos
```

If you are deploying RHCOS you'll need to define variables for splunk configuration:

```
export TF_VAR_splunk_hostname=...
export TF_VAR_splunk_sidecar_repo=...
export TF_VAR_itpaas_splunk_repo=...
```

## Running tofu
```bash
   # To begin using it, run 'init' within this directory.
   tofu init
   # If you don't intend to make any changes to the code, simply run it:
   tofu apply
   # If you plan to make changes to the code as modules/plugins, go ahead and run it:
   tofu init -upgrade
   # To destroy it run:
   tofu destroy -target aws_instance.coreos-aarch64-builder
```
