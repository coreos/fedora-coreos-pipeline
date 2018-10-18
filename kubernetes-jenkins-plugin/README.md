
This directory contains files that will set up a jenkins pipeline
using the [Kubernetes Jenkins plugin](https://github.com/jenkinsci/kubernetes-plugin).


Create a jenkins instance with a persistent volume backing store:

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

Create an image stream for the jenkins slave container so that we
don't have to pull it from the remote registry each time. This is
used for the `jnlp` container in the kubernetes plugin.

```
$ oc import-image jenkins-slave-base-centos7:latest --from=docker.io/openshift/jenkins-slave-base-centos7 --confirm
$ oc tag docker.io/openshift/jenkins-slave-base-centos7:latest jenkins-slave-base-centos7:latest --scheduled
```

Create a PVC where we will store the cache and build artifacts for
most recent builds.
```
$ oc create -f pvc.yaml
```

Create the pipeline (buildconfig with pipeline strategy) and start a build:

```
$ oc create -f kube-fcos-pipeline.yaml
$ oc start-build kube-fcos-pipeline
```

Use the web interface to view logs from builds
