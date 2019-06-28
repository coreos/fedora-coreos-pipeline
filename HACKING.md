## Setting up the pipeline

There are multiple ways to set up the pipeline. There is a single
official prod mode, which can only be instantiated in CentOS CI under
the `fedora-coreos` namespace. However, one can set up everything
equally well in a separate namespace (such as `fedora-coreos-devel`), or
a separate cluster entirely.

The main tool to deploy and update these resource is `./deploy`, which
is located in this repo. It is a simple wrapper around
`oc process/create/replace` to make deploying and updating the various
resources located in the `manifests/pipeline.yaml` OpenShift template
easier. It will set up a Jenkins pipeline job which uses the
[Kubernetes Jenkins plugin](https://github.com/jenkinsci/kubernetes-plugin).

In the following sections, the section header may indicate whether the
section applies to the local cluster case (`[LOCAL]`) or the official
prod case (`[PROD]`).

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

Note that `oc cluster up/down` will require running as root, to
communicate with the docker daemon. However, the other steps in
this guide can (and should) be run as non-root.

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

Ideally you will match the project name used for prod in CentOS CI
(`fedora-coreos`), but feel free to use a different project name
like `fedora-coreos-devel` if you'd like.

```
oc new-project fedora-coreos
```

And you're all set!

### [LOCAL] Fork the repo

If you're planning to test changes, it would be best to fork
this repo so that you do your work there. The workflow
requires a remote repo to which to push changes.

### Creating AWS credentials config

If you are in production where we upload builds to S3 OR you want to
test uploading to S3 as part of your pipeline development, you need to
create a credentials config as a secret within OpenShift.

First create a file with your secret content:

```
cat <<'EOF' > /path/to/file
[default]
aws_access_key_id=keyid
aws_secret_access_key=key
EOF
```

Then create the secret in OpenShift:

```
oc create secret generic fcos-builds-bot-aws-config --from-file=config=/path/to/file
```

### Create a Jenkins instance with a persistent volume backing store

```
oc new-app --template=jenkins-persistent \
    --param=NAMESPACE=fedora-coreos \
    --param=MEMORY_LIMIT=2Gi \
    --param=VOLUME_CAPACITY=2Gi \
    --param=JENKINS_IMAGE_STREAM_TAG=jenkins:2
```

Notice the `NAMESPACE` parameter. This makes the Jenkins master use the
image from our namespace, which we'll create in the next step. (The
reason we create the app first is that otherwise OpenShift will
automatically instantiate Jenkins with default parameters when creating
the Jenkins pipeline).

The `jenkins:2` parameter is to match the tag name in the latest
OpenShift. Some older versions of the template in OpenShift uses
`jenkins:latest`. This will no longer be needed once we are running on a
newer version of OpenShift than 3.6 in
CentOS CI. See [#32](https://github.com/coreos/fedora-coreos-pipeline/pull/32)
and [#70](https://github.com/coreos/fedora-coreos-pipeline/pull/70)
for more context).

### Creating the pipeline

If working on the production pipeline, you may simply do:

```
./deploy --official --update
```

If working in a different namespace/cluster, the command is the same,
but without the `--official` switch:


```
./deploy --update
```

You may also want to provide additional switches depending on the
circumstances. Here are some of them:

- `--prefix PREFIX`
    - The prefix to prepend to created developer-specific resources. By
      default, this will be your username, but you can provide a
      specific prefix as well.
- `--pipeline <URL>[@REF]`
    - Git source URL and optional git ref for pipeline Jenkinsfile.
- `--config <URL>[@REF]`
    - Git source URL and optional git ref for FCOS config.
- `--pvc-size <SIZE>`
    - Size of the cache PVC to create. Note that the PVC size cannot be
      changed after creation. The format is the one understood by
      Kubernetes, e.g. `30Gi`.
- `--cosa-img <FQIN>`
    - Image of coreos-assembler to use.
- `--bucket BUCKET`
    - AWS S3 bucket in which to store builds (or blank for none).

For example, to target a specific combination of pipeline, FCOS config,
cosa image, and bucket:

```
./deploy --update \
    --pipeline https://github.com/jlebon/fedora-coreos-pipeline     \
    --config https://github.com/jlebon/fedora-coreos-config@feature \
    --cosa-img quay.io/jlebon/coreos-assembler:random-tag           \
    --bucket jlebon-fcos
```

See `./deploy --help` for more information.

This will create:

1. the Jenkins master imagestream,
2. the Jenkins slave imagestream,
3. the coreos-assembler imagestream,
4. the `PersistentVolumeClaim` in which we'll cache, and
5. the Jenkins pipeline build.

The default size of the PVC is 100Gi. There is a `--pvc-size` parameter
one can use to make this smaller if you do not have enough space. E.g.
`--pvc-size 30Gi`.

We can now start a build of the Jenkins master:

```
oc start-build --follow fedora-coreos-jenkins
```

Once the Jenkins master image is built, Jenkins should start up (verify
with `oc get pods`). Once the pod is marked READY, you should be able to
login to the Jenkins UI at https://jenkins-$NAMESPACE.$CLUSTER_URL/
(`oc get route jenkins` will show the URL). As previously noted, any
password works to log in as `developer`.

It may be a good idea to set the Kubernetes plugin to
[use DNS for service names](TROUBLESHOOTING.md#issue-for-jenkins-dns-names).

### Running the pipeline

Once Jenkins is ready, we can now start the Fedora CoreOS pipeline!

```
oc start-build fedora-coreos-pipeline
```

(Or if running as a developer pipeline, it would be e.g.
`jlebon-fedora-coreos-pipeline`).

You can verify the build status using `oc get builds` or by checking the
logs in the web UI.

### Updating the pipeline

The procedure is the same for updating the pipeline:


```
./deploy --update
```

Note any value you don't pass to `deploy` will be reset to its default
value from the `manifests/pipeline.yaml` OpenShift template. This is
currently as designed (see
[#65](https://github.com/coreos/fedora-coreos-pipeline/issues/65)).

### [PROD] Update the "secret" token value in the webhook to be unique

```
oc set triggers bc/fedora-coreos-pipeline-mechanical --from-webhook
```

### [PROD] Set up webhooks/automation

- From the GitHub Settings tab for `fedora-coreos-config`, go to the
  "Webhooks" panel
- Click "Add webhook"
- In the address, type `$JENKINS_URL/github-webhook/`. So e.g.:
  https://jenkins-fedora-coreos-devel.apps.ci.centos.org/github-webhook/
- Change the Content Type from GitHub’s default `application/x-www-form-urlencoded` to `application/json`.
- Click "Add webhook"

Repeat these steps for the `fedora-coreos-pipeline` repo.

### [OPTIONAL] Set up simple-httpd

When hacking locally, it might be useful to look at the contents of the
PV to see the builds if one isn't uploading to S3. One alternative to
creating a "sleeper" pod with the PV mounted is to expose a simple httpd
server:

```
oc create -f manifests/simple-httpd.yaml
```

You'll then be able to browse the contents of the PV at:

```
http://simple-httpd-fedora-coreos.$CLUSTER_URL
```

(Or simply check the output of `oc get route simple-httpd`).
