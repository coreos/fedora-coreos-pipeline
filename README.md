# Fedora CoreOS Pipeline

This is the Jenkins pipeline configuration for
[Fedora CoreOS](https://github.com/coreos/fedora-coreos-config).

The pipeline is built around
[coreos-assembler](https://github.com/coreos/coreos-assembler).

It uses the OpenShift Jenkins template and is meant to be
fully compatible with the local dev `oc cluster up`
workflow. For more information on getting started, see
[HACKING](HACKING.md).

The production instance is running in
an [OpenShift cluster](https://jenkins-fedora-coreos-pipeline.apps.ocp.fedoraproject.org/) in Fedora's infrastructure
(though note anonymous view is blocked by default). Its raw
build output can be seen in the
[build browser](https://builds.coreos.fedoraproject.org/browser)
(but note that the latest ***supported*** version of FCOS must
be downloaded from
[the official page](https://getfedora.org/en/coreos/download/)).

To operate the production Jenkins (or more generally to access the
production namespace), you must have access to the cluster
at https://console-openshift-console.apps.ocp.fedoraproject.org/
and to the "fedora-coreos" project.

If you need access, you can open a pull request to 
https://pagure.io/fedora-infra/ansible/blob/main/f/playbooks/openshift-apps/fedora-coreos-pipeline.yml
with your [Fedora Account System username](https://accounts.fedoraproject.org/) similar to
[this one](https://pagure.io/fedora-infra/ansible/pull-request/949).

You also need one of the project admins as a sponsor, please
reach out on Libera.Chat `#fedora-coreos` channel.

Finally you will need to run the Ansible playbook from [batcave](https://docs.fedoraproject.org/en-US/infra/sysadmin_guide/sshaccess/).
```
sudo rbac-playbook -l os_control openshift-apps/fedora-coreos-pipeline.yml
```

You should also be able to run this pipeline and run it in
any OpenShift cluster that supports (potentially nested)
virtualization.

### Terminology

This repo tries to maintain a consistent set of words to
avoid confusion around different concepts with similar
names:

- `production/development/mechanical streams`: refers to the
  Fedora CoreOS streams as defined in
  https://github.com/coreos/fedora-coreos-tracker/blob/main/stream-tooling.md
- `official pipeline`: the *single* official instance of
  this pipeline code, which runs in the
  `fedora-coreos-pipeline` namespace on the Fedora OpenShift
  cluster and pushes to the `fcos-builds` bucket.
- `developer pipeline`: a pipeline stood up by a developer
  running in a separate cluster/namespace

So for example, a developer pipeline may perform e.g. a
production or development stream build, but release tooling
only cares about builds performed by the official pipeline
pushed to the official locations.

Avoid using the naked word `devel`. Always either use
`development` (if talking about the streams) or `developer`
(if talking about the pipeline).

Similarly, avoid using the word `production` alone, in
favour of `production stream` or `official pipeline`.
