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

To operate Jenkins (or more generally to access the
production namespace), you must have access to the cluster
at https://console.apps.ci.centos.org:8443 and to the projects
"fedora-coreos" and "fedora-coreos-devel".

If you need access, you can open a bug request at
https://bugs.centos.org/ against the `Buildsys` project and
`Ci.centos.org Ecosystem Testing` category.
Do note that the bug-tracker and OpenShift use different
authentication backends, so you will have separate credentials
for each.

Please specify the requested username, RBAC for projects
`fedora-coreos` and `fedora-coreos-devel`, and your GPG key
fingerprint.
You also need one of the project admins as a sponsor, please
reach out on Freenode `#fedora-coreos` channel.
