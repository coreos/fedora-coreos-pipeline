# RHCOS Versions, Streams, and Repositories

## Build Streams

| Stream | Description |
|--------|-------------|
| `c9s` | CentOS Stream 9 (upstream development) |
| `c10s` | CentOS Stream 10 (upstream development) |
| `rhel-9.2` | RHEL 9.2 based builds |
| `rhel-9.4` | RHEL 9.4 based builds |
| `rhel-9.6` | RHEL 9.6 based builds |
| `rhel-9.8` | RHEL 9.8 based builds |
| `rhel-10.2` | RHEL 10.2 based builds |

## OCP to RHEL Version Mapping

| OCP | RHEL |
|-----|------|
| 4.12 | 8.6 |
| 4.13 | 9.2 |
| 4.14 | 9.2 |
| 4.15 | 9.2 |
| 4.16 | 9.4 |
| 4.17 | 9.4 |
| 4.18 | 9.4 |
| 4.19 | 9.6 |
| 4.20 | 9.6 |
| 4.21 | 9.6 |
| 4.22 | 9.8 |

## Repositories

*GitHub -- github.com:*

| Repository | Purpose |
|------------|---------|
| `github.com/coreos/fedora-coreos-config` | Upstream FCOS manifests (inherited by RHCOS). Key files: `manifest.yaml`, `tests/kola/`, `kola-denylist.yaml` |
| `github.com/coreos/rhel-coreos-config` | RHCOS config (RHEL-specific packages). Contains `fedora-coreos-config` as a submodule. Key files: `manifest-*.yaml`, `packages-rhcos.yaml`, `tests/kola/` |
| `github.com/coreos/coreos-assembler` | Build tool (cosa) and kola test framework. Key dirs: `mantle/kola/` (tests), `src/`, `docs/` |
| `github.com/coreos/fedora-coreos-pipeline` | Jenkins pipeline definitions for both FCOS and RHCOS. Key dirs: `jobs/`, `config.yaml` |
| `github.com/openshift/os` | Node image layer (adds OCP packages). Key files: `packages-openshift.yaml`, `Containerfile`, `tests/kola/`, `extensions/` |

*GitLab -- bootc base images (upstream inputs to coreos-config repos):*

| Repository | Purpose |
|------------|---------|
| `gitlab.com/fedora/bootc/base-images` | Fedora bootc base images -- input to `fedora-coreos-config` |
| `gitlab.com/redhat/centos-stream/containers/bootc` | CentOS Stream bootc base images -- input to `fedora-coreos-config` for c9s/c10s streams |
| `gitlab.com/redhat/rhel/bifrost/rhel-bootc` | RHEL bootc base images -- input to `rhel-coreos-config` for RHEL streams |

*GitLab -- gitlab.cee.redhat.com (internal):*

| Repository | Purpose |
|------------|---------|
| `gitlab.cee.redhat.com/coreos/*` | Internal CoreOS team repos |

## Repository Relationships

- `fedora-coreos-config` is a *submodule* inside `rhel-coreos-config`
- The coreos-config repos consume bootc base images from the upstream GitLab repos as their starting point
- `rhel-coreos-config` produces the *base image* (Stage 1)
- `github.com/openshift/os` *builds FROM* the base image to create the node image (Stage 2)

## Package Definitions

*Base OS packages (Stage 1)* -- defined in `github.com/coreos/rhel-coreos-config`:
- `packages-rhcos.yaml` -- RHCOS-specific packages
- `manifest-*.yaml` -- stream-specific manifests (repos, versions)
- Inherited from `github.com/coreos/fedora-coreos-config`: `manifests/*.yaml` (modular package groups)

*OpenShift packages (Stage 2)* -- defined in `github.com/openshift/os`:
- `packages-openshift.yaml` -- OCP node packages (kubelet, cri-o, oc, etc.)
- `extensions/` -- optional extensions (usbguard, etc.)

## Test Locations

Kola tests are distributed across GitHub repositories:
- `github.com/coreos/fedora-coreos-config` `tests/kola/` -- FCOS-specific tests
- `github.com/coreos/rhel-coreos-config` `tests/kola/` -- RHCOS-specific tests
- `github.com/openshift/os` `tests/kola/` -- node image tests
- `github.com/coreos/coreos-assembler` `mantle/kola/tests/` -- core kola tests

Each config repo has a `kola-denylist.yaml` to skip known-failing tests.
