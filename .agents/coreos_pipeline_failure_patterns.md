# Failure Classification and Patterns

*Critical rule:* When a kola test passed on rerun (`rerun_failed: false`), it is flaky and NOT the root cause. Always continue investigating the build logs for the actual failure (compose error, infrastructure error, etc.).

## Failure Taxonomy

Every pipeline failure should be classified into one primary category:

- *infrastructure* -- Transient infrastructure problems: network timeouts, node failures, resource exhaustion, disk full, cloud API errors. Typically resolved by retry.
- *flake* -- Intermittent test failures that pass on rerun. Not the root cause of the build failure -- look deeper in the logs.
- *test_regression* -- A test consistently fails (including on rerun) due to a product or test code change.
- *package_change* -- A package version update in RHEL repos caused a build or test failure. Often manifests as version skew or dependency conflicts.
- *registry_auth* -- `unauthorized` or `403` errors pulling container images. Check registry credentials and secret configuration.
- *tooling* -- Failures in coreos-assembler (cosa), rpm-ostree, or other build tooling. Check for cosa version changes between good and bad builds.
- *unknown* -- Cannot determine root cause from available evidence. Requires deeper investigation.

## Kola Test Interpretation

The `jenkins_getTestResults` output includes test pass/fail status. For kola tests, the key signal is whether a failing test also failed on automatic rerun:

- *Failed on rerun (`rerun_failed: true`)* -- The test consistently fails. This is likely the root cause. Investigate package changes between the last known good build and this build.
- *Passed on rerun (`rerun_failed: false`)* -- The test is flaky but NOT the root cause of the build failure. Continue investigating the build logs for the actual failure (see critical rule above).

*Decision tree:*
1. Test failures with `rerun_failed: true` -- find last known good build, compare packages, classify as `test_regression` or `package_change`
2. Test failures with `rerun_failed: false` only -- flaky tests, NOT root cause. Search logs for compose/infrastructure errors
3. No test failures -- build/infrastructure failure, analyze logs directly

## Log Analysis Patterns

When searching build logs with `jenkins_searchBuildLog`, use these patterns:

*General errors:*
- `error:` or `FATAL:` -- build or compose error
- `failed to` or `cannot ` -- operation failures

*Infrastructure:*
- `timeout` or `timed out` -- resource or network timeout
- `Connection refused` or `503` or `500` -- service availability
- `temporarily unavailable` -- transient failures
- `No space left on device` -- disk exhaustion

*Registry/auth:*
- `unauthorized` -- registry authentication failure
- `403` or `access denied` -- permission issues

*Build stages:*
- `FAILED` or `UNSTABLE` -- stage-level failures

## Common Failure Patterns

| Pattern | Category | Typical Action |
|---------|----------|----------------|
| DNF version conflict (e.g., `requires pkg = X, but pkg Y is filtered out`) | `package_change` | Rebuild base image to pick up newer package version |
| `unauthorized` pulling images from registry | `registry_auth` | Check Jenkins credentials/secrets configuration |
| SSH timeout to remote builder | `infrastructure` | Retry; if persistent, check builder node health |
| Kola test fails consistently on one arch | `test_regression` | Check package diff, especially kernel or arch-specific packages |
| Fedora/CentOS repo timeout or HTTP 500 | `infrastructure` | Retry; transient upstream issue |
| cosa version change between good/bad builds | `tooling` | Compare cosa commits, check for build-process changes |
| GCS upload error or "Event-Based hold" | `infrastructure` | Check GCS bucket permissions and lifecycle policies |

## ROOT_CAUSE Format

When summarizing a failure's root cause, use a short, consistent label (under 60 characters). This is used for clustering related failures across builds and streams.

Examples:
- `NetworkManager version skew (RHEL 9.6 repos)`
- `SSH timeout to aarch64 remote builder`
- `chronyd.service failure in kernel-replace test`
- `registry.ci HTTP 500 during image pull`
- `DNF conflict: conmon-rs requires newer crun`
- `cosa regression in ostree commit path`

## Key Packages to Investigate

When package changes are suspected, pay special attention to:
- `kernel` / `kernel-rt` -- boot, drivers, secex, performance
- `ignition` -- firstboot, provisioning
- `coreos-installer` -- installation, s390x zipl, secex
- `ostree` / `rpm-ostree` -- upgrades, deployments
- `systemd` -- services, boot ordering
- `NetworkManager` -- networking, version skew is a common source of failures
- `cri-o` / `kubelet` -- OCP node components (Stage 2)
