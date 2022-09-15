
Here are some rough instructions for bringing up multi-arch builders.

### aarch64

The aarch64 builder runs on an AWS bare metal node. We use a bare
metal node for `/dev/kvm` access.

```bash
# Add your credentials to the environment.
HISTCONTROL='ignoreboth'
 export AWS_DEFAULT_REGION=us-east-1
 export AWS_ACCESS_KEY_ID=XXXX
 export AWS_SECRET_ACCESS_KEY=YYYYYYYY
```

Create the Ignition config

```bash
cat builder-common.bu | butane --pretty --strict > builder-common.ign
cat coreos-aarch64-builder.bu | butane --pretty --strict --files-dir=. > coreos-aarch64-builder.ign
```

Bring the instance up with appropriate details:

```bash
NAME='coreos-aarch64-builder'
AMI='ami-0cd88be9379abf352'
TYPE='a1.metal'
DISK='200'
SUBNET='subnet-0732e4cda7466a2ae'
SECURITY_GROUPS='sg-7d0b4c05'
USERDATA="${PWD}/coreos-aarch64-builder.ign"
aws ec2 run-instances                     \
    --output json                         \
    --image-id $AMI                       \
    --instance-type $TYPE                 \
    --subnet-id $SUBNET                   \
    --security-group-ids $SECURITY_GROUPS \
    --user-data "file://${USERDATA}"      \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${NAME}}]" \
    --block-device-mappings "VirtualName=/dev/xvda,DeviceName=/dev/xvda,Ebs={VolumeSize=${DISK},VolumeType=gp3}" \
    > out.json
```

Wait for the instance to come up (`a1.metal` instances can take 5-10 minutes to
come up) and log in:

```bash
INSTANCE=$(jq --raw-output .Instances[0].InstanceId out.json)
IP=$(aws ec2 describe-instances --instance-ids $INSTANCE --output json \
     | jq -r '.Reservations[0].Instances[0].PublicIpAddress')
ssh "core@${IP}"
```

Make sure the instance came up fine:

```bash
sudo systemctl --failed
```

Now that the instance is up we can re-assign the floating IP address.
This removes the IP from the existing instance (if there is one) so you'll
want to make sure no jobs are currently running on the existing instance
by checking to make sure Jenkins is idle (i.e. no build-cosa or multi-arch
aarch64 jobs are running).

```bash
# Grab the instance ID and associate the IP address
INSTANCE=$(jq --raw-output .Instances[0].InstanceId out.json)
EIP='18.233.54.49'
EIPID='eipalloc-4305254a'
aws ec2 associate-address --instance-id $INSTANCE --allow-reassociation --allocation-id $EIPID
```

Now you should be able to `ssh "core@${EIP}"`.

NOTE: Just this once ignore the ssh host key changed warning if you see it.


Once you are ready the old builder can be taken down:

```
OLDINSTANCEID=<foo> # use `aws ec2 describe-instances` to find
aws ec2 terminate-instances --instance-ids $OLDINSTANCEID
```

### ppc64le

This machine is a VM on a koji builder set up for us by Kevin Fenzi
(nirik). We'll work to automate this bringup and store the information
about how to provision this machine here in the future.

To create the Ignition config for this machine:

```bash
cat builder-common.bu | butane --pretty --strict > builder-common.ign
cat coreos-ppc64le-builder.bu | butane --pretty --strict --files-dir=. > coreos-ppc64le-builder.ign
```

To connect to this machine first add 
[custom ssh configuration](https://docs.fedoraproject.org/en-US/infra/sysadmin_guide/sshaccess/#_ssh_configuration)
for connecting to machines via Fedora bastion hosts.

When done, tweak the list of `10.3.*` addresses in the list to include
`10.3.171.*`. Then you should be able to connect:

```
ssh core@10.3.171.40
```


### s390x

We use an s390x instance in IBM Cloud to do our builds and tests. First get an
`ibmcloud` shell session. A container for this exists at a not very well
documented location on `icr.io`:

```bash
podman run -it --name ibmcloud icr.io/continuous-delivery/pipeline/pipeline-base-image:latest /bin/bash

# Install plugins for cos and is
ibmcloud plugin install cloud-object-storage
ibmcloud plugin install infrastructure-service

# Login (use auth via browser)
ibmcloud login --sso
```

Create the Ignition config in a separate terminal and copy it into the
container where ibmcloud is running:

```bash
cat builder-common.bu | butane --pretty --strict > builder-common.ign
cat coreos-s390x-builder.bu | butane --pretty --strict --files-dir=. > coreos-s390x-builder.ign
podman cp coreos-s390x-builder.ign ibmcloud:/root/coreos-s390x-builder.ign
```

Now we can start the instance:

```bash
NAME="coreos-s390x-builder-$(date +%Y%m%d)"
VPC='r038-a29e1c05-8a07-4ddc-8216-c75cbd459daa'
ZONE='ca-tor-1' # s390x only available in Toronto in North America
PROFILE='bz2-8x32'
IMAGE='r038-369d6b9c-f0d1-4daf-bda4-252df3aa4728'
SUBNET='02q7-1df6496a-b363-4f3b-9204-0a2b06855a2f'
ibmcloud is instance-create $NAME $VPC $ZONE $PROFILE $SUBNET --output json --image-id $IMAGE \
     --boot-volume '{"name": "my-boot-vol-1", "volume": {"capacity": 200, "profile": {"name": "general-purpose"}}}' \
     --user-data @coreos-s390x-builder.ign > out.json
```


Assign the backup floating IP to the instance so we can log in to it
and then log in:

```bash
NIC=$(jq --raw-output .primary_network_interface.id out.json)
ibmcloud is floating-ip-update coreos-s390x-builder-backup --nic $NIC

INSTANCE=$(jq --raw-output .id out.json)
IP=$(ibmcloud is instance $INSTANCE --output json \
     | jq -r '.primary_network_interface.floating_ips[0].address')
echo "You can now SSH to core@${IP}"
```

NOTE: Just this once ignore the ssh host key changed warning if you see it.

Make sure the instance came up fine:

```bash
sudo systemctl --failed
```

Now that the instance is up we can re-assign the floating IP address.
This removes the IP from the existing instance (if there is one) so you'll
want to make sure no jobs are currently running on the existing instance
by checking to make sure Jenkins is idle (i.e. no build-cosa or multi-arch
s390x jobs are running).

```bash
NIC=$(jq --raw-output .primary_network_interface.id out.json)
ibmcloud is floating-ip-update coreos-s390x-builder --nic $NIC

INSTANCE=$(jq --raw-output .id out.json)
IP=$(ibmcloud is instance $INSTANCE --output json \
     | jq -r '.primary_network_interface.floating_ips[0].address')
echo "You can now SSH to core@${IP}"
```

NOTE: Just this once ignore the ssh host key changed warning if you see it.

Once you are ready the old builder can be taken down:

```bash
OLDINSTANCEID=<foo> # use `ibmcloud is instances` to find
ibmcloud is instance-delete $OLDINSTANCEID
```

### x86_64

The x86_64 builder runs on an AWS node without `/dev/kvm` access. Right now this
builder only builds the COSA container image and does not do FCOS builds so it
doesn't need `/dev/kvm`. If that need changes then we can switch the instance type
in the future.

```bash
# Add your credentials to the environment.
HISTCONTROL='ignoreboth'
 export AWS_DEFAULT_REGION=us-east-1
 export AWS_ACCESS_KEY_ID=XXXX
 export AWS_SECRET_ACCESS_KEY=YYYYYYYY
```

Create the Ignition config

```bash
cat builder-common.bu | butane --pretty --strict > builder-common.ign
cat coreos-x86_64-builder.bu | butane --pretty --strict --files-dir=. > coreos-x86_64-builder.ign
```

Bring the instance up with appropriate details:

```bash
NAME='coreos-x86_64-builder'
AMI='ami-068df0b4ca626835d'
TYPE='c6a.xlarge'
DISK='100'
SUBNET='subnet-0732e4cda7466a2ae'
SECURITY_GROUPS='sg-7d0b4c05'
USERDATA="${PWD}/coreos-x86_64-builder.ign"
aws ec2 run-instances                     \
    --output json                         \
    --image-id $AMI                       \
    --instance-type $TYPE                 \
    --subnet-id $SUBNET                   \
    --security-group-ids $SECURITY_GROUPS \
    --user-data "file://${USERDATA}"      \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${NAME}}]" \
    --block-device-mappings "VirtualName=/dev/xvda,DeviceName=/dev/xvda,Ebs={VolumeSize=${DISK},VolumeType=gp3}" \
    > out.json
```

Wait for the instance to come up and log in:

```bash
INSTANCE=$(jq --raw-output .Instances[0].InstanceId out.json)
IP=$(aws ec2 describe-instances --instance-ids $INSTANCE --output json \
     | jq -r '.Reservations[0].Instances[0].PublicIpAddress')
ssh "core@${IP}"
```

Make sure the instance came up fine:

```bash
sudo systemctl --failed
```

Now that the instance is up we can re-assign the floating IP address.
This removes the IP from the existing instance (if there is one) so you'll
want to make sure no jobs are currently running on the existing instance
by checking to make sure Jenkins is idle (i.e. no build-cosa jobs are running).

```bash
# Grab the instance ID and associate the IP address
INSTANCE=$(jq --raw-output .Instances[0].InstanceId out.json)
EIP='34.199.112.205'
EIPID='eipalloc-01bfbeca9d47b2202'
aws ec2 associate-address --instance-id $INSTANCE --allow-reassociation --allocation-id $EIPID
```

Now you should be able to `ssh "core@${EIP}"`.

NOTE: Just this once ignore the ssh host key changed warning if you see it.


Once you are ready the old builder can be taken down:

```
OLDINSTANCEID=<foo> # use `aws ec2 describe-instances` to find
aws ec2 terminate-instances --instance-ids $OLDINSTANCEID
```
