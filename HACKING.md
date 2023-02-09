## Setting up the pipeline

There are multiple ways to set up the pipeline. There is a single
official prod mode, which can only be instantiated in Fedora under
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

It is preferable to match the project name used for prod in Fedora
(`fedora-coreos-pipeline`), but feel free to use a different project
name like `fedora-coreos-devel` if you'd like.

```
oc new-project fedora-coreos-pipeline
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
cat <<'EOF' > /path/to/aws-build-upload-config
[default]
aws_access_key_id=keyid
aws_secret_access_key=key
EOF
```

Then create the secret in OpenShift:

```
oc create secret generic aws-build-upload-config \
    --from-literal=filename=aws_config_file \
    --from-file=data=/path/to/aws-build-upload-config
oc label secret/aws-build-upload-config \
    jenkins.io/credentials-type=secretFile
oc annotate secret/aws-build-upload-config \
    jenkins.io/credentials-description="AWS build upload credentials config"
```

We also have a second AWS config that can be used for running kola
tests. If you have a single account that has enough permissions for
both then you can use the same account for both uploading builds and
running kola tests (i.e. re-use secret from above). If not then
you can use a second set of credentials for the kola tests.

```
cat <<'EOF' > /path/to/aws-kola-tests-config
[default]
aws_access_key_id=keyid
aws_secret_access_key=key
EOF

oc create secret generic aws-kola-tests-config \
    --from-literal=filename=aws_config_file \
    --from-file=data=/path/to/aws-kola-tests-config
oc label secret/aws-kola-tests-config \
    jenkins.io/credentials-type=secretFile
oc annotate secret/aws-kola-tests-config \
    jenkins.io/credentials-description="AWS kola tests credentials config"
```

NOTE: For the prod pipeline these secrets can be found in BitWarden

### [OPTIONAL] Creating AWS GovCloud credentials configs

If you want to upload to AWS GovCloud you can create a separate
set of credentials for performing that action:

```
cat <<'EOF' > /path/to/aws-govcloud-image-upload-config
[default]
aws_access_key_id=keyid
aws_secret_access_key=key
EOF
```

Then create the secret in OpenShift:

```
oc create secret generic aws-govcloud-image-upload-config \
    --from-literal=filename=aws_config_file \
    --from-file=data=/path/to/aws-govcloud-image-upload-config
oc label secret/aws-govcloud-image-upload-config \
    jenkins.io/credentials-type=secretFile
oc annotate secret/aws-govcloud-image-upload-config \
    jenkins.io/credentials-description="AWS GovCloud image upload credentials config"
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
oc create secret generic gcp-image-upload-config \
    --from-literal=filename=gcp_config_file \
    --from-file=data=/path/to/upload-secret
oc label secret/gcp-image-upload-config \
    jenkins.io/credentials-type=secretFile
oc annotate secret/gcp-image-upload-config \
    jenkins.io/credentials-description="GCP Image upload credentials config"
```

We also have a second GCP config that can be used for running kola tests. If you have a
single account that you'd like to use for both image uploading and tests you can do that
assuming they have enough permissions.

```
oc create secret generic gcp-kola-tests-config \
    --from-literal=filename=gcp_config_file \
    --from-file=data=/path/to/kola-secret
oc label secret/gcp-kola-tests-config \
    jenkins.io/credentials-type=secretFile
oc annotate secret/gcp-kola-tests-config \
    jenkins.io/credentials-description="GCP kola tests credentials config"
```

NOTE: For the prod pipeline these secrets can be found in BitWarden

### [OPTIONAL] Creating Azure credentials configs

If you want to do image uploads or run kola tests against Azure
images you need to create a file called `azureCreds.json`. See the
[kola docs](https://github.com/coreos/coreos-assembler/blob/main/docs/mantle/credentials.md#azure)
for more information on those files.

Once you have the azureCreds.json for connecting to Azure, create the secrets in OpenShift:

```
oc create secret generic azure-image-upload-config \
    --from-literal=filename=azure_config_file \
    --from-file=data=/path/to/upload-secret
oc label secret/azure-image-upload-config \
    jenkins.io/credentials-type=secretFile
oc annotate secret/azure-image-upload-config \
    jenkins.io/credentials-description="Azure image upload credentials config"

oc create secret generic azure-kola-tests-config \
    --from-literal=filename=azure_config_file \
    --from-file=data=/path/to/kola-secret
oc label secret/azure-kola-tests-config \
    jenkins.io/credentials-type=secretFile
oc annotate secret/azure-kola-tests-config \
    jenkins.io/credentials-description="Azure kola tests credentials config"
```

NOTE: For the prod pipeline these secrets can be found in BitWarden

### [OPTIONAL] Creating OpenStack credentials configs

If you want to run kola tests against OpenStack images you need to
create a secret
([`clouds.yaml` format](https://docs.openstack.org/python-openstackclient/latest/configuration/index.html#clouds-yaml))
for talking with the OpenStack host.

Once you have the yaml file that represents the credentials for connecting
to your OpenStack instance, create the secret in OpenShift:

```
oc create secret generic openstack-kola-tests-config \
    --from-literal=filename=openstack_config_file \
    --from-file=data=/path/to/clouds.yaml
oc label secret/openstack-kola-tests-config \
    jenkins.io/credentials-type=secretFile
oc annotate secret/openstack-kola-tests-config \
    jenkins.io/credentials-description="OpenStack kola tests credentials config"
```

NOTE: For the prod pipeline these secrets can be found in BitWarden

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
TOKEN=<token>
EMOJI=<:emojistring:>
oc create secret generic slack-api-token --from-literal=text="${TOKEN}"
oc label secret/slack-api-token \
    jenkins.io/credentials-type=secretText
oc annotate secret/slack-api-token \
    jenkins.io/credentials-description="Slack API token"
oc annotate secret/slack-api-token \
    jenkins.io/emoji-prefix="${EMOJI}"
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
dir=$(mktemp -d)
echo -n '18.233.54.49' > "${dir}/host"
echo -n 'builder'      > "${dir}/user"
echo -n '1001'         > "${dir}/uid"
cat /path/to/sshkey > "${dir}/sshkey"
```

Then create the secrets in OpenShift:

```
ARCH='aarch64'

oc create secret generic "coreos-${ARCH}-builder-host" \
    --from-file=text="${dir}/host"
oc label "secret/coreos-${ARCH}-builder-host" \
    jenkins.io/credentials-type=secretText
oc annotate "secret/coreos-${ARCH}-builder-host" \
    jenkins.io/credentials-description="Hostname or IP for the $ARCH builder"

oc create secret generic "coreos-${ARCH}-builder-uid" \
    --from-file=text="${dir}/uid"
oc label "secret/coreos-${ARCH}-builder-uid" \
    jenkins.io/credentials-type=secretText
oc annotate "secret/coreos-${ARCH}-builder-uid" \
    jenkins.io/credentials-description="UID for the user for $ARCH builder"

oc create secret generic "coreos-${ARCH}-builder-sshkey" \
    --from-file=username="${dir}/user" \
    --from-file=privateKey="${dir}/sshkey"
oc label "secret/coreos-${ARCH}-builder-sshkey" \
    jenkins.io/credentials-type=basicSSHUserPrivateKey
oc annotate "secret/coreos-${ARCH}-builder-sshkey" \
    jenkins.io/credentials-description="Username and Private SSH Key for $ARCH builder"
```

In the prod pipeline we create secrets for aarch64, ppc64le,
and x86_64 (for the build-cosa job).

NOTE: For x86_64 you need to use a hyphen/dash (`-`) instead of an
      underscore in the secret name: `coreos-x86-64-builder`.

NOTE: For the prod pipeline these secrets can be found in BitWarden

### [PROD] GitHub webhook shared secret

Create a shared webhook secret using e.g. `uuidgen -r`:

```
# use `echo -n` to make sure no newline is in the secret
echo -n "$(uuidgen -r)" > secret
oc create secret generic github-webhook-shared-secret --from-file=text=secret
oc label secret/github-webhook-shared-secret \
    jenkins.io/credentials-type=secretText
oc annotate secret/github-webhook-shared-secret \
    jenkins.io/credentials-description="GitHub Webhook Shared Secret"
```

NOTE: the secret will be used again when setting up the generic webhooks in the next section.

### [PROD] Setup webhooks for the Generic webhook plugin

Add a new webhook to the following repos:

- [`coreos/fedora-coreos-config`](https://github.com/coreos/fedora-coreos-config.git) for the `build-fcos-buildroot` job.
- [`coreos/coreos-assembler`](https://github.com/coreos/coreos-assembler.git) for the `build-cosa` job.

In this case we'll re-use the GitHub webhook shared secret text that
was created in the previous section. Add a webhook for each repo that
follows:

- Payload URL: `https://<JENKINS_URL>/generic-webhook-trigger/invoke?token=<JOB>`
    - replace `<JENKINS_URL>` with the URL of the jenkins instance
    - replace `<JOB>` with `build-fcos-buildroot` or `build-cosa` based on the repo you are adding the webhook to.
- Content Type: `application/json`
- Secret: Use the secret text from the GitHub webhook shared secret above

### [PROD] Create OSContainer image push secret

This secret is used to push the oscontainer and others to Quay.io.
The secret can be obtained from the `oscontainer-push-registry-secret` in BitWarden.

After obtaining the secret data you can create the Kubernetes secret via:

```
oc create secret generic oscontainer-push-registry-secret \
    --from-literal=filename=dockercfg \
    --from-file=data=oscontainer-push-registry-secret
oc label secret/oscontainer-push-registry-secret \
    jenkins.io/credentials-type=secretFile
oc annotate secret/oscontainer-push-registry-secret \
    jenkins.io/credentials-description="Push secret for registry for CoreOS OSContainer"
```

### [PROD] Create OSContainer image push secret for old location

This secret is used to push the oscontainer and others to the old registry. The
secret can be obtained from the `oscontainer-push-old-registry-secret` in
BitWarden.

After obtaining the secret data you can create the Kubernetes secret via:

```
oc create secret generic oscontainer-push-old-registry-secret \
    --from-literal=filename=dockercfg \
    --from-file=data=oscontainer-push-old-registry-secret
oc label secret/oscontainer-push-old-registry-secret \
    jenkins.io/credentials-type=secretFile
oc annotate secret/oscontainer-push-old-registry-secret \
    jenkins.io/credentials-description="Push secret for old registry for CoreOS OSContainer"
```

### [PROD] Create COSA image push secret

This secret is used to push COSA container image builds and FCOS buildroot
container image builds to Quay.io. The secret can be obtained from
the `cosa-push-registry-secret` in BitWarden.

After obtaining the secret data you can create the Kubernetes secret via:

```
oc create secret generic cosa-push-registry-secret \
    --from-literal=filename=dockercfg \
    --from-file=data=cosa-push-registry-secret
oc label secret/cosa-push-registry-secret \
    jenkins.io/credentials-type=secretFile
oc annotate secret/cosa-push-registry-secret \
    jenkins.io/credentials-description="Registry push secret for COSA and FCOS buildroot containers"
```

### [PROD] Create fedora-messaging configuration

First create the Fedora Messaging configuration secret:

```
oc create secret generic fedora-messaging-config \
    --from-literal=filename=fedmsg.toml \
    --from-file=data=configs/fedmsg.toml
oc label secret/fedora-messaging-config \
    jenkins.io/credentials-type=secretFile
oc annotate secret/fedora-messaging-config \
    jenkins.io/credentials-description="Fedora messaging fedmsg.toml"
```

Then add the client secrets:

```
oc create secret generic fedora-messaging-coreos-x509-cert \
    --from-literal=serverCaCertificate='unused' \
    --from-file=clientCertificate=coreos.crt \
    --from-file=clientKeySecret=coreos.key
oc label secret/fedora-messaging-coreos-x509-cert \
    jenkins.io/credentials-type=x509ClientCert
oc annotate secret/fedora-messaging-coreos-x509-cert \
    jenkins.io/credentials-description="Fedora messaging CoreOS x509 client cert"
```

You can obtain `coreos.crt` and `coreos.key` from BitWarden.

### [PROD] Create coreosbot GitHub token secrets

Create the CoreOS Bot (coreosbot) GitHub token secrets (this
corresponds to the "Fedora CoreOS pipeline" token of
coreosbot, with just `public_repo` and `admin:repo_hook`;
these creds are available in BitWarden).

We create two secrets here. One Username/Password (used by bump-lockfile and
sync-stream-metadata) and one SecretText (used by GitHub Plugin):

```
TOKEN=<TOKEN>
oc create secret generic github-coreosbot-token-text --from-literal=text=${TOKEN}
oc label secret/github-coreosbot-token-text \
    jenkins.io/credentials-type=secretText
oc annotate secret/github-coreosbot-token-text \
    jenkins.io/credentials-description="GitHub coreosbot token as text/string"

oc create secret generic github-coreosbot-token-username-password \
    --from-literal=username=coreosbot \
    --from-literal=password=${TOKEN}
oc label secret/github-coreosbot-token-username-password \
    jenkins.io/credentials-type=usernamePassword
oc annotate secret/github-coreosbot-token-username-password  \
    jenkins.io/credentials-description="GitHub coreosbot token as username/password"
```

### [PROD, OPTIONAL] Create additional root CA certificate secret

If an additional root CA certificate is needed, create it as
a secret. This assumes `ca.crt` is a file in the working directory:

```
oc create secret generic additional-root-ca-cert \
    --from-literal=filename=ca.crt --from-file=data=ca.crt
oc label secret/additional-root-ca-cert \
    jenkins.io/credentials-type=secretFile
oc annotate secret/additional-root-ca-cert \
    jenkins.io/credentials-description="Root certificate for XXX"
```

The description can be customized. All other fields must be
exactly as is.

### Create a Jenkins instance with a persistent volume backing store

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
using a development cluster, it normally isn't, and you can drop it. For
the Fedora prod cluster, use `ocs-storagecluster-ceph-rbd` as shown
above.

Now, create the Jenkins configmap:

```
oc create configmap jenkins-casc-cfg --from-file=jenkins/config
```

### Creating the pipeline

If working on the production pipeline, you may simply do:

```
./deploy
```

You may also want to provide additional switches depending on the
circumstances. Below are the available options:

- `--pipeline <URL>[@REF]`
    - Git source URL and optional git ref for pipeline Jenkinsfile.
- `--pipecfg <URL>[@REF]`
    - Git source URL and optional git ref for pipeline configuration, if
      not in-tree.

For example, to target a specific pipeline:

```
./deploy \
    --pipeline https://github.com/jlebon/fedora-coreos-pipeline
```

Or to build with the canonical pipeline but a different
pipecfg:

```
./deploy \
    --pipecfg https://github.com/jlebon/fedora-coreos-pipecfg
```

See `./deploy --help` for more information.

This will create:

1. the Jenkins controller imagestream,
2. the Jenkins agent imagestream,
3. the Jenkins agent BuildConfig (if a root CA cert was provided),
4. the jenkins-config configmap.

If a root CA cert was provided, we need to build the base images that
will bake in the cert in the controller and agent:

```
oc start-build --follow jenkins-with-cert
oc start-build --follow jenkins-agent-base-with-cert
```

We can now start an S2I build of the Jenkins controller:

```
oc start-build --follow jenkins-s2i
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

Once the seed job on Jenkins finishes successfully we can now start the
Fedora CoreOS pipeline! Go into the Jenkins UI and start the `build`
job.

### Updating the pipeline

The procedure is the same for updating the pipeline:


```
./deploy
```

Note any value you don't pass to `deploy` will be reset to its default
value from the `manifests/pipeline.yaml` OpenShift template. This is
currently as designed (see
[#65](https://github.com/coreos/fedora-coreos-pipeline/issues/65)).

### Triggering builds remotely

We use the OpenShift Login plugin for authentication. This plugin maps
OpenShift users to Jenkins users, including service accounts. So we can
use the `jenkins` service account (or really, any service account with
the `edit` role):

```
jenkins_uid=$(oc get sa jenkins -o jsonpath="{.metadata.uid}")
token_data=$(oc get secrets -o json | jq -r "[.items[] | select(
    .type == \"kubernetes.io/service-account-token\" and
    .metadata.annotations[\"kubernetes.io/service-account.name\"] == \"jenkins\" and
    .metadata.annotations[\"kubernetes.io/service-account.uid\"] == \"${jenkins_uid}\"
)] | .[0].data.token" | base64 -d)
curl -H "Authorization: Bearer ${token_data}" $JENKINS_URL/job/build/buildWithParameters --data STREAM=4.13
```

See the
[OpenShift Login plugin docs](https://github.com/jenkinsci/openshift-login-plugin#non-browser-access)
for more information.

### Nuking everything

One can leverage Kubernetes labels to delete all objects
related to the pipeline:

```
oc delete all -l app=coreos-pipeline
```

This won't delete a few resources. Notably the PVC and the
SA. Deleting the PVC is usually not recommended since it may
require cluster admin access to reallocate it in the future
(if it doesn't, feel free to delete it!). To delete the
other objects:

```
oc delete serviceaccounts -l app=coreos-pipeline
oc delete rolebindings -l app=coreos-pipeline
oc delete configmaps -l app=coreos-pipeline
```
