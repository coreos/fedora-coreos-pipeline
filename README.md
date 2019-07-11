This is the Jenkins pipeline configuration for
[Fedora CoreOS](https://github.com/coreos/fedora-coreos-config).

The production instance is running in
[CentOS CI](https://jenkins-fedora-coreos.apps.ci.centos.org).

The pipeline is built around
[coreos-assembler](https://github.com/coreos/coreos-assembler).

It uses the OpenShift Jenkins template and is meant to be
fully compatible with the local dev `oc cluster up`
workflow. For more information on getting started, see
[HACKING](HACKING.md).
