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
section applies only to the local cluster case (`[LOCAL]`) or the
official prod case (`[PROD]`).

You'll want to be sure you have kubevirt available in your cluster.  See
[this section of the coreos-assembler docs](https://github.com/coreos/coreos-assembler/blob/main/README.md#getting-started---prerequisites).

### Using a production OpenShift cluster

This is recommended for production pipelines, and also gives you
a lot of flexibility.  The coreos-assembler document above has
multiple options for this.  To be clear, we would also likely
support running on "vanilla" Kubernetes if someone interested showed
up wanting that.

### [LOCAL] Set up an OpenShift cluster

The easiest way to set up your own local OCP4 cluster for developing on
the pipeline is using
[CodeReady Containers](https://code-ready.github.io/crc),
though the author has not yet tried setting up the pipeline on that
footprint yet. (You'll need to allocate the VM at least ~14G of RAM.)

Alternatively, you can use the OpenShift installer directly to create a
full cluster either in libvirt or GCP (for nested virt). For more
details, see:

https://github.com/openshift/installer/tree/master/docs/dev/libvirt
https://github.com/coreos/coreos-assembler/blob/main/doc/openshift-gcp-nested-virt.md

Once you have a cluster up, you will want to deploy the OpenShift CNV
operator for access to Kubevirt:

Install the operator using the web console:
https://docs.openshift.com/container-platform/4.9/virt/install/installing-virt-web.html#installing-virt-web

Altenatively, you can install the operator using the CLI:
https://docs.openshift.com/container-platform/4.9/virt/install/installing-virt-cli.html#installing-virt-cli 

You can learn more about the OpenShift CNV operator here:
https://docs.openshift.com/container-platform/4.9/virt/virt-learn-more-about-openshift-virtualization.html

### [LOCAL] Create your project

It is preferable to match the project name used for prod in CentOS CI
(`fedora-coreos`), but feel free to use a different project name like
`fedora-coreos-devel` if you'd like.

```
oc new-project fedora-coreos
```

### [LOCAL] Fork the repo

If you're planning to test changes, it would be best to fork
this repo so that you do your work there. The workflow
requires a remote repo to which to push changes.

### [OPTIONAL] Creating AWS credentials configs

If you are in production where we upload builds to S3 OR you want to
test uploading to S3 as part of your pipeline development, you need to
create a credentials config as a secret within OpenShift.

First create files with your secret content:

```
mkdir dir
cat <<'EOF' > dir/config
[default]
aws_access_key_id=keyid
aws_secret_access_key=key
EOF
echo keyid > dir/accessKey
echo key > dir/secretKey
```

We expose it in different ways (as an AWS config file and as direct
fields) because those credentials are used both directly as Jenkins
credentials (which use the direct fields) and by e.g. `cosa`, which
accepts an AWS config file.

Then create the secret in OpenShift:

```
oc create secret generic aws-fcos-builds-bot-config --from-file=dir
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

### [OPTIONAL] Creating GCP credentials configs

If you are in production where we upload images to GCP OR you want to
test uploading to GCP as part of your pipeline development, you need to
create a upload credentials for a service account as a secret within OpenShift.
For more information on creating a service account see
[the Google Cloud Docs](https://cloud.google.com/iam/docs/creating-managing-service-accounts#creating).

Once you have the json file that represents the credentials for your service account
from GCP, create the secret in OpenShift:

```
oc create secret generic gcp-image-upload-config --from-file=config=/path/to/upload-secret
```

We also have a second GCP config that can be used for running kola tests. If you have a
single account that you'd like to use for both image uploading and tests you can do that
assuming they have enough permissions.

```
oc create secret generic gcp-kola-tests-config --from-file=config=/path/to/kola-secret
```

### [OPTIONAL] Creating OpenStack credentials configs

If you want to run kola tests against OpenStack images you need to
create a secret
([`clouds.yaml` format](https://docs.openstack.org/python-openstackclient/latest/configuration/index.html#clouds-yaml))
for talking with the OpenStack host.

Once you have the yaml file that represents the credentials for connecting
to your OpenStack instance, create the secret in OpenShift:

```
oc create secret generic openstack-kola-tests-config --from-file=config=/path/to/clouds.yaml
```

### [OPTIONAL] Allocating S3 storage

If you want to store builds persistently, now is a good time to allocate
S3 storage.  See the [upstream coreos-assembler docs](https://github.com/coreos/coreos-assembler/blob/main/README-design.md)
around build architecture.

Today, the FCOS pipeline is oriented towards having its own
bucket; this will likely be fixed in the future.  But using your
credentials, you should now do e.g.:

```
$ aws s3 mb s3://my-fcos-bucket
```

And provide it to `--bucket` below when creating the pipeline with the `deploy` script.

To install the aws CLI needed for this steps see [installing or updating the latest version of the AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html).
To configure the aws CLI with your credentials see [quick configuration with aws configure](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-quickstart.html).

### [OPTIONAL] Slack integration

If you want to be able to have build status messages appear in Slack,
create a `slack-api-token` secret:

```
$ echo -n "$TOKEN" > slack-token
$ oc create secret generic slack-api-token --from-file=token=slack-token
```

You can obtain a token when creating a new instance of the Jenkins CI
app in your Slack workspace. The token used for the `coreos.slack.com`
workspace is available by going to the [Jenkins CI
app](https://slack.com/apps/A0F7VRFKN-jenkins-ci) then to
"Configuration" and "FCOS Pipeline".

### [OPTIONAL] Creating a secret for multi-arch builders

We can farm off builds to machines of other architectures. We use
SSH for this. To create a secret for one of these you can first
create files with your secret content:

```
mkdir dir
echo '18.233.54.49' > dir/host
echo 'builder'      > dir/user
echo '1001'         > dir/uid
cat /path/to/sshkey > dir/sshkey
```

Then create the secret in OpenShift:

```
oc create secret generic fcos-aarch64-builder --from-file=dir
```

### [PROD] GitHub webhook shared secret

Create a shared webhook secret using e.g. `uuidgen -r`:

```
uuidgen -r > secret
oc create secret generic github-webhook-shared-secret --from-file=secret
```

### [PROD] Create quay.io image push secret

This secret is used to push the resulting OCI image to Quay.io

1. Obtain the file `oscontainer-secret` from BitWarden.
2. Run: `$ oc create secret generic oscontainer-secret --from-file=dockercfg=oscontainer-secret`.

### Create a Jenkins instance with a persistent volume backing store

For CentOS CI:

```
oc new-app --file=manifests/jenkins.yaml \
  --param=NAMESPACE=fedora-coreos
```

For Fedora:

```
oc new-app --file=manifests/jenkins.yaml \
  --param=NAMESPACE=fedora-coreos-pipeline \
  --param=STORAGE_CLASS_NAME=ocs-storagecluster-ceph-rbd
```

Notice the `NAMESPACE` parameter. This makes the Jenkins controller use the
image from our namespace, which we'll create in the next step. (The
reason we create the app first is that otherwise OpenShift will
automatically instantiate Jenkins with default parameters when creating
the Jenkins pipeline).

The `STORAGE_CLASS_NAME` may be required depending on the cluster. If
using a development cluster or the CentOS CI cluster, it normally isn't,
and you can drop it. For the Fedora prod cluster, use
`ocs-storagecluster-ceph-rbd` as shown above.

Now, create the Jenkins configmap:

```
oc create configmap jenkins-casc-cfg --from-file=jenkins/config
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

1. the Jenkins controller imagestream,
2. the Jenkins agent imagestream,
3. the coreos-assembler imagestream, and
4. the Jenkins pipeline build.

We can now start a build of the Jenkins controller:

```
oc start-build --follow jenkins
```

Once the Jenkins controller image is built, Jenkins should start up (verify
with `oc get pods`). Once the pod is marked READY, you should be able to
login to the Jenkins UI at https://jenkins-$NAMESPACE.$CLUSTER_URL/
(`oc get route jenkins` will show the URL). As previously noted, any
password works to log in as `developer`.

It may be a good idea to set the Kubernetes plugin to
[use DNS for service names](TROUBLESHOOTING.md#issue-for-jenkins-dns-names).

### Running the pipeline

Once Jenkins is ready, make sure that the seed job has been run successfully.

Once the seed job on Jenkins finishes successfully we can now start the Fedora CoreOS pipeline!

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

### [PROD] Create fedora-messaging configuration

First create the configmap:

```
oc create configmap fedora-messaging-cfg --from-file=configs/fedmsg.toml
```

Then add the client secrets:

```
oc create secret generic fedora-messaging-coreos-key \
  --from-file=coreos.crt --from-file=coreos.key
```

You can obtain `coreos.crt` and `coreos.key` from BitWarden.

### [PROD] Create coreosbot GitHub token secret

Create the CoreOS Bot (coreosbot) GitHub token secret (this
correspond to the "Fedora CoreOS pipeline" token of
coreosbot, with just `public_repo` and `admin:repo_hook`;
these creds are available in BitWarden):

```
echo $coreosbot_token > token
oc create secret generic github-coreosbot-token --from-file=token
```

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
