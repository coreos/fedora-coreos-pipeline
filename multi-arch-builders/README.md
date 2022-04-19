
Here are some rough instructions for bringing up a multi-arch builder:

```bash
# Add your credentials to the environment.
HISTCONTROL='ignoreboth'
 export AWS_DEFAULT_REGION=us-east-1
 export AWS_ACCESS_KEY_ID=XXXX
 export AWS_SECRET_ACCESS_KEY=YYYYYYYY


# create the Ignition config
cat fcos-aarch64-builder.bu | butane --pretty --strict > fcos-aarch64-builder.ign

# Bring the instance up with appropriate details
NAME='fcos-aarch64-builder'
AMI='ami-0cd88be9379abf352'
TYPE='a1.metal'
DISK='200'
SUBNET='subnet-0732e4cda7466a2ae'
SECURITY_GROUPS='sg-7d0b4c05'
USERDATA="${PWD}/fcos-aarch64-builder.ign"
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

Make sure the instance came up fine and wait for the COSA image build
to complete:

```bash
sudo systemctl --failed
sudo machinectl shell builder@
journalctl --user -f # to watch image build
podman images # to view built image
```

Now that the instance is up and COSA is built we can re-assign the
floating IP address. This removes the IP from the existing instance
(if there is one) so you'll want to make sure no jobs are currently
running on the existing instance by checking to make sure Jenkins is
idle (i.e. no multi-arch aarch64 jobs are running).

```bash
# Grab the instance ID and associate the IP address
INSTANCE=$(jq --raw-output .Instances[0].InstanceId out.json)
EIP='18.233.54.49'
EIPID='eipalloc-4305254a'
aws ec2 associate-address --instance-id $INSTANCE --allow-reassociation --allocation-id $EIPID
```

Now you should be able to `ssh "core@${EIP}"`.

NOTE: Just this once ignore the ssh host key changed warning if you see it.


Once a build in Jenkins goes through the new builder we can tear down
the old builder (if there is one). You can do it via the web interface
or via CLI like so:

```
OLDINSTANCEID=<foo> # use `aws ec2 describe-instances` to find
aws ec2 terminate-instances --instance-ids $OLDINSTANCEID
```
