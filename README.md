This is the Jenkins pipeline configuration for
[Fedora CoreOS](https://github.com/coreos/fedora-coreos-config).

The pipeline is built around
[coreos-assembler](https://github.com/coreos/coreos-assembler).

It uses the OpenShift Jenkins template and is meant to be
fully compatible with the local dev `oc cluster up`
workflow. For more information on getting started, see
[HACKING](HACKING.md).

The production instance is running in
[CentOS CI](https://jenkins-fedora-coreos.apps.ci.centos.org)
(though note anonymous view is blocked by default).

To have access to Jenkins (or more generally to the
production namespace), you must open a bug request at
https://bugs.centos.org/ against the `Buildsys` project and
`Ci.centos.org Ecosystem Testing` category and ask to be
added to the `fedora-coreos` and `fedora-coreos-devel`
projects. After one of the project admins sponsors you,
you'll be given access.
