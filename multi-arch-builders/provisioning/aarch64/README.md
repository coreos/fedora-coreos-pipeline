# OpenTofu

 OpenTofu is a Terraform fork, is an open-source infrastructure as code (IaC) tool
 lets you define both cloud and on-prem resources in human-readable configuration files
 that you can version, reuse, and share.

 To proceed with the next steps, ensure that 'tofu' is installed on your system.
 See: https://github.com/opentofu/opentofu/releases

## Before starting

### AWS credentials

```bash
# Add your credentials to the environment.
# Be aware for aarch64 the region is us-east-2
HISTCONTROL='ignoreboth'
 export AWS_DEFAULT_REGION=us-east-2
 export AWS_ACCESS_KEY_ID=XXXX
 export AWS_SECRET_ACCESS_KEY=YYYYYYYY
```

Make sure your AMI user has access to this policies:

```json
{
	"Version": "2012-10-17",
	"Statement": [
		{
			"Effect": "Allow",
			"Action": "ec2:*",
			"Resource": "*"
		}
	]
}
```

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
## Generating additional resources with unique names

When rerunning the Tofu configuration any changes will be
applied to the existing resources. If you intend to add a new
resource with a different name, please be aware that TOFU doesn't
support interpolation in resource names.

To achieve this, you'll need to manually edit the resource name
in the Tofu configuration.

```
resource "aws_instance" "coreos-aarch64-builder"
```
Make sure the resource name is unique, in this case
if I already have a resource named `coreos-aarch64-builder`,
I need to change it to `coreos-aarch64-devel-builder` for example.

I may also want to update the project var:

```
variable "project" {
 type    = string
 default = "coreos-aarch64-devel-builder"
}
```

After it, I can rerun `tofu apply`.

The same is validated to all resources types.
