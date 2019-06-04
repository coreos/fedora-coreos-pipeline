The `manifests/pipeline.yaml` file contains an OpenShift template that
will set up a Jenkins pipeline using the
[Kubernetes Jenkins plugin](https://github.com/jenkinsci/kubernetes-plugin).

These instructions will cover both local developer workflows, as well as
interacting with the production pipeline in CentOS CI. A section header
may indicate that the section only applies to one of the two workflows.

Another option if one simply wants to test a different COSA image or
FCOS config or pipeline code (but otherwise maintain the manifest the
same) is to create a developer pipeline alongside the production one. To
do this, you *must* use the `devel-up` script. For example:

```
./devel-up --update \
        --pipeline https://github.com/jlebon/fedora-coreos-pipeline \
        --config https://github.com/jlebon/fedora-coreos-config@feature \
        --cosa-img quay.io/jlebon/coreos-assembler:random-tag
```

Now carrying on with the instructions for setting up the environment
from scratch...

### [LOCAL] Set up an OpenShift cluster

First, make sure to install `oci-kvm-hook` on your host system (not in a
pet container). This is required to ensure that the pipeline has access
to `/dev/kvm`:

```
rpm-ostree install oci-kvm-hook # if on OSTree-based system
dnf install -y oci-kvm-hook # if on traditional
```

We will use `oc cluster up` to set up a local cluster for testing. To do
this, simply obtain the OpenShift v3.6.1 binary from
[here](https://github.com/openshift/origin/releases/tag/v3.6.1). We want
to match the OCP version running in CentOS CI.

`openshift-origin-client-tools` is enough for `oc cluster up`.

If you have not done so already, follow the instructions in the
[Prerequisites](https://github.com/openshift/origin/blob/v4.0.0-alpha.0/docs/cluster_up_down.md#prerequisites)
and [Getting Started](https://github.com/openshift/origin/blob/v4.0.0-alpha.0/docs/cluster_up_down.md#getting-started)
sections to set up your environment and start the Docker daemon.

If on Fedora > 28 and Openshift OKD < 3.11, the
`/usr/lib/systemd/system/docker.service` unit file needs
overriding to use the `cgroupfs` cgroup driver instead
of `systemd`. (See https://bugzilla.redhat.com/show_bug.cgi?id=1558425#c22
for more info). Add the override as follows:

```
systemctl edit --full docker.service
```

Change the following line (under `ExecStart`) from:

```
--exec-opt native.cgroupdriver=systemd \
```

to:

```
--exec-opt native.cgroupdriver=cgroupfs \
```

Restart the docker daemon for the override
to take effect:

```
systemctl restart docker.service
```

And now, bring up a v3.6.1 cluster (again, to match CentOS CI):

```
oc cluster up --version v3.6.1
```

To have persistent configs and data, I would recommend specifying the
dirs to use.

```
oc cluster up --version v3.6.1 \
    --host-config-dir=/srv/oc/config \
    --host-data-dir=/srv/oc/data \
    --host-pv-dir=/srv/oc/pv
```

Then when doing `oc cluster up` in the future, you can use
`--use-existing-config`:

```
oc cluster up --version v3.6.1 \
    --host-config-dir=/srv/oc/config \
    --host-data-dir=/srv/oc/data \
    --host-pv-dir=/srv/oc/pv \
    --use-existing-config
```

Once complete, something like the
following will show:

```
OpenShift server started.

The server is accessible via web console at:
    https://127.0.0.1:8443

You are logged in as:
    User:     developer
    Password: <any value>

To login as administrator:
    oc login -u system:admin
```

Once the cluster is up, we need to mark our only node (`localhost`) as
oci-kvm-hook enabled. To do this:

```
oc login -u system:admin
oc patch node localhost -p '{"metadata":{"labels":{"oci_kvm_hook":"allowed"}}}'
```

You can now sign in as as `developer`:

```
oc login -u developer https://127.0.0.1:8443
```

(Any password will work). The IP address to log in here may differ
according to the output from `oc cluster up`.

We'll want to match the project name used in CentOS CI:

```
oc new-project fedora-coreos
oc delete project myproject
```

And you're all set!

### [LOCAL] Fork the repo

If you're planning to test changes, it would be best to fork
this repo so that you do your work there. The workflow
requires a remote repo to which to push changes.

### Create a Jenkins instance with a persistent volume backing store

```
oc new-app --template=jenkins-persistent \
    --param=NAMESPACE=fedora-coreos \
    --param=MEMORY_LIMIT=2Gi \
    --param=VOLUME_CAPACITY=2Gi
```

Notice the `NAMESPACE` parameter. This makes the Jenkins master use the
image from our namespace, which we'll create in the next step. (The
reason we create the app first is that otherwise OpenShift will
automatically instantiate Jenkins with default parameters when creating
the Jenkins pipeline).

### Create the pipeline from the template

If working on the production pipeline, you may simply do:

```
oc new-app --file=manifests/pipeline.yaml
```

If working on a local cluster you will want to define the `DEVEL_PREFIX`
parameter to be e.g. `$USER-`. The pipeline will simply not run without
a `DEVEL_PREFIX` set if it is not running in prod mode.

There are additional useful parameters for the devel case. For example,
to use a custom pipeline repo, you'll want to override the
`PIPELINE_REPO_URL` and `PIPELINE_REPO_REF` parameters:

```
oc new-app --file=manifests/pipeline.yaml \
    --param=DEVEL_PREFIX=$(whoami)- \
    --param=PIPELINE_REPO_URL=https://github.com/jlebon/fedora-coreos-pipeline \
    --param=PIPELINE_REPO_REF=my-feature-branch
```

This template creates:

1. the Jenkins master imagestream,
2. the Jenkins slave imagestream,
3. the coreos-assembler imagestream,
4. the `PersistentVolumeClaim` in which we'll cache and compose, and
5. the Jenkins pipeline build.

The default size of the PVC is 100Gi. There is a `PVC_SIZE`
parameter one can use to make this smaller if you do not
have enough space. E.g. `--param=PVC_SIZE=30Gi`.

We can now start a build of the Jenkins master:

```
oc start-build --follow fedora-coreos-jenkins
```

Once the Jenkins master image is built, Jenkins should start up (verify
with `oc get pods`). Once the pod is marked READY, you should be able to
login to the Jenkins UI at https://jenkins-fedora-coreos.$CLUSTER_URL/
(`oc get route jenkins` will show the URL). As previously noted, any
password works to log in as `developer`.

It may be a good idea to set the Kubernetes plugin to
[use DNS for service names](TROUBLESHOOTING.md#issue-for-jenkins-dns-names).

### Start build using the CLI

Once Jenkins is ready, the Fedora CoreOS pipeline should've already been
started. You can verify this by checking the job in the web UI, or by
running `oc get builds`. You can also manually trigger a new build
using:

```
oc start-build fedora-coreos-pipeline
```

Use the web interface to view logs from builds.

### [PROD] Update the "secret" token values in the webhooks to be unique

```
oc set triggers bc/fedora-coreos-pipeline --from-github
oc set triggers bc/fedora-coreos-pipeline --from-webhook
```

### [PROD] Set up webhooks/automation

Grab the URLs of the webhooks from `oc describe` and set up webhook
in github.

- `oc describe bc/fedora-coreos-pipeline` and grab the `Webhook GitHub` URL
- From the GitHub web console for the configs repository.
- Select Add Webhook from Settings → Webhooks & Services.
- Paste the webook URL output into the Payload URL field.
- Change the Content Type from GitHub’s default `application/x-www-form-urlencoded` to `application/json`.
- Click Add webhook.

### [OPTIONAL] Set up simple-httpd

When hacking locally, it might be useful to look at the contents of the
PV to see the builds since one isn't rsync'ing to an artifact server.
One alternative to creating a "sleeper" pod with the PV mounted is to
expose a simple httpd server:

```
oc create -f manifests/simple-httpd.yaml
```

You'll then be able to browse the contents of the PV at:

```
http://simple-httpd-fedora-coreos.$CLUSTER_URL
```

(Or simply check the output of `oc get route simple-httpd`).
