
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
DISK='100'
SUBNET='subnet-0732e4cda7466a2ae'
SECURITY_GROUPS='sg-7d0b4c05'
USERDATA="${PWD}/fcos-aarch64-builder.ign"
EIP='18.233.54.49'
EIPID='eipalloc-4305254a'
aws ec2 run-instances                     \
    --output json                         \
    --image-id $AMI                       \
    --instance-type $TYPE                 \
    --subnet-id $SUBNET                   \
    --security-group-ids $SECURITY_GROUPS \
    --user-data "file://${USERDATA}"      \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${NAME}}]" \
    --block-device-mappings "VirtualName=/dev/xvda,DeviceName=/dev/xvda,Ebs={VolumeSize=${DISK}}" \
    > out.json

# Grab the instance ID and associate the IP address
INSTANCE=$(jq --raw-output .Instances[0].InstanceId out.json)
aws ec2 associate-address --instance-id $INSTANCE --allow-reassociation --allocation-id $EIPID


# ssh into the instance
# NOTE: Just this once ignore the ssh host key changed warning if you see it.
ssh core@18.233.54.49
