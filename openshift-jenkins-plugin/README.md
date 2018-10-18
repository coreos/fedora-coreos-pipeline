**NOTE* We have chosen to go with the kubernetes jenkins plugin for
        now so this directory is mostly here in case we want to leverage this
        work in the future for some reason. It will probably get deleted at
        some point.

This directory contains files that will set up a jenkins pipeline
using the [OpenShift Jenkins Pipeline plugin](https://github.com/openshift/jenkins-client-plugin)


```
$ oc create -f pvc.yaml
$ oc import-image dustymabe-coreos-assembler:latest --from=quay.io/dustymabe/coreos-assembler --confirm
$ oc tag quay.io/dustymabe/coreos-assembler:latest dustymabe-coreos-assembler:latest --scheduled
$ oc create -f openshift-fcos-pipeline.yaml
$ oc start-build openshift-fcos-pipeline
``
