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

You'll want to be sure you have KVM available in your cluster.  See
[this section of the coreos-assembler docs](https://github.com/coreos/coreos-assembler/blob/master/README.md#getting-started---prerequisites).

### Using a production OpenShift cluster

This is recommended for production pipelines, and also gives you
a lot of flexibility.  The coreos-assembler document above has
multiple options for this.  To be clear, we would also likely
support running on "vanilla" Kubernetes if someone interested showed
up wanting that.

### [LOCAL] Set up an OpenShift cluster

If you're using `oc cluster up` (which is an older OpenShift with Docker)
the easiest is to install `oci-kvm-hook` on your host system (not in a
pet container).  NOTE: The production path for this in modern clusters is
the [KVM device plugin](https://github.com/kubevirt/kubernetes-device-plugins/blob/master/docs/README.kvm.md)
linked in the `[PROD]` docs.

```
rpm-ostree install oci-kvm-hook # if on OSTree-based system
yum -y install oci-kvm-hook # if on traditional
```

We will use `oc cluster up` to set up a local cluster for testing. To do
this, obtain the OpenShift v3.6.1 binary from
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

### [OPTIONAL] Creating AWS credentials configs

If you are in production where we upload builds to S3 OR you want to
test uploading to S3 as part of your pipeline development, you need to
create a credentials config as a secret within OpenShift.

First create a file with your secret content:

```
cat <<'EOF' > /path/to/upload-secret
[default]
aws_access_key_id=keyid
aws_secret_access_key=key
EOF
```

Then create the secret in OpenShift:

```
oc create secret generic aws-fcos-builds-bot-config --from-file=config=/path/to/upload-secret
```

We also have a second AWS config that can be used for running kola
tests. If you have a single account that has enough permissions for
both then you can use the same account for both uploading builds and
running kola tests (i.e. re-use `upload-secret` from above. If not then
you can use a second set of credentials for the kola tests.

```
cat <<'EOF' > /path/to/kola-secret
[default]
aws_access_key_id=keyid
aws_secret_access_key=key
EOF

oc create secret generic aws-fcos-kola-bot-config --from-file=config=/path/to/kola-secret
```

### [OPTIONAL] Allocating S3 storage

If you want to store builds persistently, now is a good time to allocate
S3 storage.  See the [upstream coreos-assembler docs](https://github.com/coreos/coreos-assembler/blob/master/README-design.md)
around build architecture.

Today, the FCOS pipeline is oriented towards having its own
bucket; this will likely be fixed in the future.  But using your
credentials, you should now do e.g.:

```
$ aws s3 mb my-fcos-bucket
```

And provide it to `--bucket` below.

### [OPTIONAL] Slack integration

If you want to be able to have build status messages appear in Slack,
create a `slack-api-token` secret:

```
$ echo -n "$TOKEN" > slack-token
$ oc create secret generic slack-api-token --from-file=token=slack-token
```

You can obtain a token when creating a new instance of the Jenkins CI
app in your Slack workspace.

### Create a Jenkins instance with a persistent volume backing store

```
oc new-app --file=manifests/jenkins.yaml --param=NAMESPACE=fedora-coreos
```

Notice the `NAMESPACE` parameter. This makes the Jenkins master use the
image from our namespace, which we'll create in the next step. (The
reason we create the app first is that otherwise OpenShift will
automatically instantiate Jenkins with default parameters when creating
the Jenkins pipeline).

Now, create the Jenkins configmap:

```
oc create configmap jenkins-casc-cfg --from=file=jenkins/config
```

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
- `--kvm-selector=kvm-device-plugin`:
    - Use this if you're using the KVM device plugin (modern
      Kubernetes/OpenShift 4+).
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
oc start-build --follow jenkins
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

### [PROD] Create fedora-messaging configuration

First create the configmap:

```
oc create configmap fedora-messaging-cfg --from-file=fedmsg.toml
```

Then add the client secrets:

```
oc create secret generic fedora-messaging-coreos-key \
  --from-file=coreos.crt --from-file=coreos.key
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

### Nuking everything

One can leverage Kubernetes labels to delete all objects
related to the pipeline:

```
oc delete all -l app=fedora-coreos
```

This won't delete a few resources. Notably the PVC and the
SA. Deleting the PVC is usually not recommended since it may
require cluster admin access to reallocate it in the future
(if it doesn't, feel free to delete it!). To delete the
other objects:

```
oc delete serviceaccounts -l app=fedora-coreos
oc delete rolebindings -l app=fedora-coreos
oc delete configmaps -l app=fedora-coreos
```
