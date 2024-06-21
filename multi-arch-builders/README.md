
Here are some rough instructions for bringing up multi-arch builders.

### aarch64

The aarch64 builder runs on an AWS bare metal node (we use a bare
metal node for `/dev/kvm` access) and deployed via terraform/tofu.
Change directory into and follow the directions at:

- [Provisioning aarch64 builder](provisioning/aarch64/README.md)

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

We've occasionally seen some failures and/or capacity issues so we'll
document here variables for `us-east-1` and `ca-tor-1`. If you use
`ca-tor-1` you may run into issues with yum mirrors being slow in tests
because the IP address block there is thought to be in France and not
Toronto when doing Geo IP lookups.

```bash
IMAGE='r038-28097bd5-500b-45f1-8d48-99b3d51e3b60'
ZONE='ca-tor-1'
VPC='r038-992efef6-1b41-4b58-8a3f-0112c6b770ca'
SUBNET='02q7-bef3b4b8-095b-4f90-a0e3-7e4aad0a509a'
SG='r038-24bc65f4-f8a5-4d70-a1cf-657b623257cc'
```

```bash
IMAGE='r014-f0c6df32-7255-465b-a5b0-0e52231a6d49'
ZONE='us-east-1'
VPC='r014-9c9a7b94-3270-4bcb-b344-be748c3c06d2'
SUBNET='0757-6e54b3bb-107a-4bd7-818c-94462c445f49'
SG='r014-b7cffdea-525e-4649-98f0-1e71368bfd4a'
```

Now we can start the instance:

```bash
NAME="coreos-s390x-builder-$(date +%Y%m%d)"
PROFILE='bz2-8x32'
ibmcloud is instance-create $NAME $VPC $ZONE $PROFILE $SUBNET --output json --image-id $IMAGE \
     --boot-volume '{"name": "my-boot-vol-1", "volume": {"capacity": 200, "profile": {"name": "general-purpose"}}}' \
     --sgs $SG --user-data @coreos-s390x-builder.ign > out.json
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

The x86_64 builder runs in AWS. It is currently not used to build
Fedora CoreOS, but is used to build and push the x86_64 version
of various container images. This detail means that this builder
doesn't need to be an AWS bare metal node for `/dev/kvm` access.
It is deployed via terraform/tofu. Change directory into and follow
the directions at:

- [Provisioning x86_64 builder](provisioning/x86_64/README.md)
