The `manifests/` directory contains OpenShift manifests that will set up a Jenkins pipeline
using the [Kubernetes Jenkins plugin](https://github.com/jenkinsci/kubernetes-plugin).

Create a Jenkins instance with a persistent volume backing store:

```
$ oc new-app --template=jenkins-persistent --param=MEMORY_LIMIT=2Gi --param=VOLUME_CAPACITY=2Gi
```

Create an image stream for the coreos-assembler container. It's nice
to use an image stream so that we can pull from the local container
registry rather than from a remote registry each time.

Also add `--scheduled` to the image so it gets updated when the remote
tag in the remote registry gets updated.

```
$ oc import-image dustymabe-coreos-assembler:latest --from=quay.io/dustymabe/coreos-assembler --confirm
$ oc tag quay.io/dustymabe/coreos-assembler:latest dustymabe-coreos-assembler:latest --scheduled
```

Create an image stream for the Jenkins slave container so that we
don't have to pull it from the remote registry each time. This is
used for the `jnlp` container in the kubernetes plugin.

```
$ oc import-image jenkins-slave-base-centos7:latest --from=docker.io/openshift/jenkins-slave-base-centos7 --confirm
$ oc tag docker.io/openshift/jenkins-slave-base-centos7:latest jenkins-slave-base-centos7:latest --scheduled
```

Create a PVC where we will store the cache and build artifacts for
most recent builds.
```
$ oc create -f manifests/pvc.yaml
```

Create the pipeline (buildconfig with pipeline strategy) and start a build:

```
$ oc create -f manifests/bc.yaml
```

Update the "secret" token values in the webooks to be unique

``` 
$ oc set triggers bc/kubernetes-fcos-pipeline --from-github
$ oc set triggers bc/kubernetes-fcos-pipeline --from-webhook
```

Grab the URLs of the webhooks from `oc describe` and set up webhook
in github.

- `oc describe bc/kubernetes-fcos-pipeline` and grab the `Webhook GitHub` URL
- From the GitHub web console for the configs repository.
- Select Add Webhook from Settings → Webhooks & Services.
- Paste the webook URL output into the Payload URL field.
- Change the Content Type from GitHub’s default `application/x-www-form-urlencoded` to `application/json`.
- Click Add webhook.

Start build using the CLI:

```
$ oc start-build kubernetes-fcos-pipeline
```

Use the web interface to view logs from builds.
