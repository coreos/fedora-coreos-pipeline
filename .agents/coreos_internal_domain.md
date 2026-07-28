# RHCOS Engineering Domain Knowledge

## What is RHCOS?

RHEL CoreOS (RHCOS) is the operating system for OpenShift Container Platform worker and control-plane nodes. It is an immutable, container-focused OS based on RHEL, built using rpm-ostree and designed for automated, unattended operation.

RHCOS shares its upstream foundation with Fedora CoreOS (FCOS). The `fedora-coreos-config` repository provides the base manifests and tests; `rhel-coreos-config` layers RHEL-specific content on top via a submodule relationship.

## Key Repositories

*GitHub (github.com):*
- `github.com/coreos/fedora-coreos-config` -- upstream FCOS manifests, tests, systemd units
- `github.com/coreos/rhel-coreos-config` -- RHCOS-specific packages and config (contains `fedora-coreos-config` as submodule)
- `github.com/coreos/coreos-assembler` -- build tooling (cosa), kola test framework, qemu harness
- `github.com/coreos/fedora-coreos-pipeline` -- Jenkins pipeline definitions for both FCOS and RHCOS builds
- `github.com/openshift/os` -- node image Containerfile, OCP packages (kubelet, cri-o, oc), extensions, node image tests

*GitLab -- bootc base images (upstream inputs):*
- `gitlab.com/fedora/bootc/base-images` -- Fedora bootc base images, input to `fedora-coreos-config`
- `gitlab.com/redhat/centos-stream/containers/bootc` -- CentOS Stream bootc base images, input for c9s/c10s streams
- `gitlab.com/redhat/rhel/bifrost/rhel-bootc` -- RHEL bootc base images, input to `rhel-coreos-config` for RHEL streams

*GitLab -- internal (gitlab.cee.redhat.com):*
- `gitlab.cee.redhat.com/coreos/*` -- internal CoreOS team repos

## OCP to RHEL Version Mapping

Early OCP releases were built on a specific RHEL version:
- OCP 4.13->4.15 = RHEL 9.2
- OCP 4.16->4.18 = RHEL 9.4
- OCP 4.19->4.21 = RHEL 9.6

Newer releases may support multiple major RHEL versions:

- OCP 4.22 = RHEL 9.8, RHEL 10.2
- OCP 5.0  = RHEL 9.8, RHEL 10.2

This mapping is important for CVE tracking, package compatibility, and understanding which streams correspond to which OCP versions.

## CVE Workflow

RHCOS CVEs are tracked across two Jira projects:

1. *OCPBUGS (component=RHCOS)* -- tracks CVEs that affect RHCOS in the context of OpenShift. Summary format: `CVE-YYYY-NNNNN rhcos [openshift-X.Y]`
2. *RHEL project* -- tracks the corresponding RHEL vulnerability issues. These track the fix in the underlying RHEL package.

CVE matching process:
- Extract the CVE ID and OCP version from the OCPBUGS issue summary
- Map the OCP version to the corresponding RHEL version
- Search the RHEL project for a vulnerability issue matching the same CVE ID and RHEL version
- Link the OCPBUGS issue to the RHEL issue (Blocks relationship)
- When the RHEL issue is closed with a fix, the RHCOS issue can be resolved once the fixed package is picked up in a new RHCOS build

## Team Processes

- Pipeline monitoring is tracked in the COS Jira project with weekly "Pipeline Monitoring" parent tasks
- Build failures are filed as subtasks with labels: `flake-infrastructure`, `flake-test`, `bug`
- Builds run daily via the `build-mechanical` scheduler at 10:00 UTC
- The pipeline serves both Fedora CoreOS and RHEL CoreOS builds from the same Jenkins infrastructure
