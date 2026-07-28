# RHCOS Build Pipeline Architecture

## Two-Stage Build Process

RHCOS is built in two stages:

*Stage 1 -- Base Image* (`build` + `build-arch` jobs)
- Input: `github.com/coreos/rhel-coreos-config` repository (contains `fedora-coreos-config` as submodule), which in turn consumes bootc base images from upstream GitLab repos (`gitlab.com/redhat/rhel/bifrost/rhel-bootc` for RHEL streams, `gitlab.com/redhat/centos-stream/containers/bootc` for CentOS streams, `gitlab.com/fedora/bootc/base-images` for Fedora)
- Output: Bootable container with RHEL/CentOS Stream content only (no OpenShift components)
- The `build` job runs for x86_64 and triggers `build-arch` for other architectures

*Stage 2 -- Node Image* (`build-node-image` job)
- Input: Base image from Stage 1 + `github.com/openshift/os` Containerfile
- Adds OpenShift packages: kubelet, cri-o, oc, etc.
- Output: `rhel-coreos` or `stream-coreos` image in OCP release payload

## Jenkins Job Hierarchy

Understanding the hierarchy is critical for correct triage:

- `build` -- Main base image build for x86_64. Triggers `build-arch` for other architectures. If a `build-arch` child fails, the parent `build` also fails -- always check the child job for the real error.
- `build-arch` -- Architecture-specific base builds (aarch64, ppc64le, s390x). Triggered by `build`. Leaf job -- kola tests run here. Analyze directly.
- `build-node-image` -- Node image build (adds OCP packages). Independent pipeline -- does NOT trigger or relate to `build-arch`. When investigating, analyze its console log directly.
- `release` -- Release builds for production.
- `build-mechanical` -- Scheduler job (see Build Scheduling below). Not a build itself.

*Critical rules:*
- When `build` fails, check which `build-arch` child failed and analyze that child -- the parent log usually just says "downstream failed."
- `build-node-image` is completely separate from `build`/`build-arch`. A `build-arch` job running at the same time is coincidental, not related.
- Always verify the stream matches when correlating jobs. A `build-node-image` for stream `4.21-9.6` cannot be caused by a `build-arch` for stream `rhel-10.2`.

## Architectures

- `x86_64` -- built by `build` job
- `aarch64`, `ppc64le`, `s390x` -- built by `build-arch`

## Build Scheduling

The `build-mechanical` job is the main scheduler:
- Runs daily at 10:00 UTC (cron: `0 10 * * *`)
- Source: `jobs/build-mechanical.Jenkinsfile` in this repo
- Triggers `build` jobs *sequentially* for all mechanical streams

Execution order: `c10s` -> `c9s` -> `rhel-10.2` -> `rhel-9.8` -> `rhel-9.6`

Each build takes 2-3 hours, so later streams start much later:
- `c10s`: ~10:00 UTC
- `rhel-9.6`: ~17:00-18:00 UTC (last in queue)

## FORCE Parameter

The `FORCE` parameter controls whether to rebuild when no changes are detected:
- `false` (default): Skip build if no config changes detected (shows "no new build")
- `true`: Always rebuild regardless of detected changes

When FORCE is needed: forcing a rebuild to pick up new packages from repos, or recovering from a failed build with stale state.

How cosa detects changes: checks if source config commit changed and if package manifest/lockfile changed. To pick up RHEL repo package updates, trigger a build with `FORCE=true` -- cosa only tracks config commits and lockfile changes, so mechanical streams (which lack strict lockfiles) require a forced rebuild to incorporate new repo content.

## Versionlock Mechanism

During `build-node-image`, packages from the base image are versionlocked to prevent unexpected upgrades:

1. `build` creates base image with packages at specific versions (e.g., `NetworkManager-1.52.0-9`)
2. `build-node-image` runs `rpm-ostree experimental compose treefile-apply`
3. This creates versionlocks for ALL packages in the base image
4. New packages can be installed, but locked packages cannot be upgraded

*Version skew problem:* If a new package in the repos requires a newer version of a locked package, DNF fails. Example: `NetworkManager-ovs` requires `NetworkManager = 1.52.0-10`, but `NetworkManager-1.52.0-9` is locked. Fix: rebuild the base image to pick up the newer version.

## Jenkins MCP Tools

Use these tools for pipeline operations:
- `jenkins_getJobs` -- list all jobs and health status
- `jenkins_getJob` -- detailed job info (health report, last builds, parameters)
- `jenkins_getBuild` -- build metadata (parameters, trigger cause, duration, result)
- `jenkins_getBuildLog` -- console log with cursor-based pagination
- `jenkins_searchBuildLog` -- regex search in console logs
- `jenkins_getTestResults` -- kola test results for a build
- `jenkins_getBuildChangeSets` -- SCM changes in a build
- `jenkins_getStatus` -- Jenkins instance health
- `jenkins_getQueueItem` -- queue item details
- `jenkins_triggerBuild` -- trigger a new build (requires human approval)
- `jenkins_rebuildBuild` -- rerun with same parameters (requires human approval)
- `jenkins_updateBuild` -- update build display name/description
