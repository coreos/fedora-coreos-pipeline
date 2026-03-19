## Konflux Migration

FCOS is progressively migrating its pipeline to [Konflux](https://konflux-ci.dev/),
a secure, SLSA-compliant build system. This migration will enhance supply chain security
by providing signed build provenance, automated vulnerability scanning, and policy-as-code
enforcement.

### Migration Phases

| Phase | Container Build | Disk Images | Tests   | Release | Status          | Tracker |
|-------|-----------------|-------------|---------|---------|-----------------|---------|
| 1     | Konflux         | Jenkins     | Jenkins | Jenkins | Done            | [#2031](https://github.com/coreos/fedora-coreos-tracker/issues/2031) |
| 2     | Konflux         | Konflux     | Jenkins | Jenkins | **In Progress** | [#2125](https://github.com/coreos/fedora-coreos-tracker/issues/2125) |
| 3     | Konflux         | Konflux     | Konflux | Jenkins | Planned         | |
| 4     | Konflux         | Konflux     | Konflux | Konflux | Planned         | |

### Architecture Overview

```
══════════════════════════════════════════════════════════════════════════════════════
                              CURRENT WORKFLOW (Phase 1)
══════════════════════════════════════════════════════════════════════════════════════

 GITHUB              KONFLUX                                         JENKINS
────────────────────────────────────────────────────────────────────────────────────────
    │                    │                                               │
    │   PR/Push          │                                               │
    │───────────────────►│                                               │
    │                    │                                               │
    │               ┌────┴────┐                                          │
    │               │   PAC   │                                          │
    │               └────┬────┘                                          │
    │                    │                                               │
    │               ┌────┴─────────────────┐                             │
    │               │     PipelineRun      │                             │
    │               │  • clone-repository  │                             │
    │               │  • prepare-build-ctx │                             │
    │               │  • prefetch-deps     │                             │
    │               │  • multi-arch build  │                             │
    │               │  • etc               │                             │
    │               └────┬─────────────────┘                             │
    │                    │  pushed to                                    │
    │                    ▼                                               │
    │         ┌─────────────────────┐                                    │
    │         │ quay.io/konflux-    │                                    │
    │         │ fedora/coreos-tenant│                                    │
    │         │ (intermediate)      │                                    │
    │         └─────────────────────┘                                    │
    │                    │                                               │
    │               ┌────┴────┐                                          │
    │               │   ITS   │ (optional)                               │
    │               └────┬────┘                                          │
    │                    │                                               │
    │               ┌────┴────────┐                                      │
    │               │ ReleasePlan │                                      │
    │               └────┬────────┘                                      │
    │                    │                                               │
    │                    ▼                                               │
    │         ┌─────────────────────┐    triggers                        │
    │         │ quay.io/coreos-     │───────────────────────────────────►│
    │         │ devel/fedora-coreos │                                    │
    │         │ (devel)             │                                    │
    │         └─────────────────────┘                              ┌─────┴─────┐
    │                                                              │  Import   │
    │                                                              └─────┬─────┘
    │                                                                    │
    │                                                              ┌─────┴─────┐
    │                                                              │   Build   │
    │                                                              │ disk imgs │
    │                                                              └─────┬─────┘
    │                                                                    │
    │                                                              ┌─────┴─────┐
    │                                                              │   Test    │
    │                                                              └─────┬─────┘
    │                                                                    │
    │                                                              ┌─────┴─────┐
    │                                                              │  Release  │
    │                                                              └───────────┘
```

#### Container Registries

| Registry | Purpose |
|----------|---------|
| [`quay.io/konflux-fedora/coreos-tenant/`](https://quay.io/organization/konflux-fedora?tab=repositories&q=coreos-tenant) | **Intermediate registry** - Stores all artifacts built by Tekton pipelines. Created via the `ImageRepository` CRD defined in the [ProjectDevelopmentStreamTemplate](#projectdevelopmentstreamtemplate). |
| [`quay.io/coreos-devel/fedora-coreos`](https://quay.io/repository/coreos-devel/fedora-coreos) | **Devel registry** - Stores images built and released by Konflux. Used by Jenkins for downstream processing. |
| [`quay.io/fedora/fedora-coreos`](https://quay.io/repository/fedora/fedora-coreos) | **Final registry** - Official public registry for end users. Images are pushed here by Jenkins after successful release. |


> [!NOTE]
> The images in the devel registry (`coreos-devel/fedora-coreos`) and final registry (`fedora/fedora-coreos`) repositories
> are exactly identical. The difference is that the final registry images are the official artifacts
> pushed by Jenkins as part of the release process. The devel registry repository is expected to be deprecated
> once Konflux takes over the release responsibility (Phase 4).

### Tenant Infrastructure Configuration

The Konflux tenant configuration for FCOS is where all Konflux-specific OpenShift CRDs
are defined in GitOps mode. The configuration is stored in the
[tenants-config](https://gitlab.com/fedora/infrastructure/konflux/tenants-config) repository.

**Location**: [`cluster/kfluxfedorap01/coreos-tenant/fedora-coreos/`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/cluster/kfluxfedorap01/coreos-tenant/fedora-coreos)

#### Fedora Konflux Cluster

The Fedora Konflux instance runs on a ROSA (Red Hat OpenShift on AWS) cluster. Key characteristics:

- **Konflux components**: Deployed from [infra-deployments](https://gitlab.com/fedora/infrastructure/konflux/infra-deployments)
- **Authentication**: Integrated with Fedora Account System (FAS)
- **Multi-architecture support**: Provisioned for `x86_64`, `aarch64`, `ppc64le`, and `s390x`

For more details (web UI, OpenShift console, ArgoCD, etc.), see the
[infra-deployments README](https://gitlab.com/fedora/infrastructure/konflux/infra-deployments).

> [!WARNING]
> The Fedora-specific [infra-deployments](https://gitlab.com/fedora/infrastructure/konflux/infra-deployments)
> repository will be deprecated in favor of the upstream [redhat-appstudio/infra-deployments](https://github.com/redhat-appstudio/infra-deployments).
> A new Fedora Konflux cluster is being deployed, tracked in [KONFLUX-11098](https://issues.redhat.com/browse/KONFLUX-11098).

#### Directory Structure

```
fedora-coreos/
├── kustomization.yaml
├── project.yaml                    # Project definition
├── template.yaml                   # ProjectDevelopmentStreamTemplate
├── ec-policy.yaml                  # EnterpriseContractPolicy
├── devstreams/                     # ProjectDevelopmentStream definitions
│   ├── kustomization.yaml
│   ├── testing-devel.yaml
│   ├── testing.yaml
│   ├── stable.yaml
│   ├── next.yaml
│   ├── next-devel.yaml
│   ├── branched.yaml
│   └── rawhide.yaml
└── releaseplans/                   # ReleasePlan configurations
    ├── kustomization.yaml
    ├── base/
    │   └── releaseplan.yaml
    ├── testing-devel/
    ├── testing/
    ├── stable/
    ├── next/
    ├── next-devel/
    ├── branched/
    └── rawhide/
```

#### Konflux Resources

##### Project

Defines the `fedora-coreos` project in Konflux.

- **File**: [`project.yaml`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/blob/main/cluster/kfluxfedorap01/coreos-tenant/fedora-coreos/project.yaml)

##### ProjectDevelopmentStreamTemplate

We use a [`ProjectDevelopmentStreamTemplate`](https://konflux-ci.dev/docs/reference/kube-apis/project-controller/#projectdevelopmentstreamtemplate)
to maintain a simple and consistent directory structure. This template automatically
generates the following Konflux resources for each stream:

| Resource | Description |
|----------|-------------|
| **Application** | Represents the FCOS stream application |
| **Component** | Build configuration with Pipelines-as-Code annotations |
| **ImageRepository** | Intermediate container image repository storing all Tekton pipeline artifacts |
| **IntegrationTestScenario** | Enterprise Contract policy validation |

- **File**: [`template.yaml`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/blob/main/cluster/kfluxfedorap01/coreos-tenant/fedora-coreos/template.yaml)

> [!NOTE]
> The `ReleasePlan` CRD is not currently supported in the `ProjectDevelopmentStreamTemplate`.
> This is why ReleasePlans are managed separately using Kustomize overlays in the
> [`releaseplans/`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/cluster/kfluxfedorap01/coreos-tenant/fedora-coreos/releaseplans) directory.

##### ProjectDevelopmentStream

Each FCOS stream has its own `ProjectDevelopmentStream` that references the template
with the appropriate version/branch value.

**Configured streams**: `testing-devel`, `testing`, `stable`, `next`, `next-devel`,
`branched`, `rawhide`

- **Files**: [`devstreams/`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/cluster/kfluxfedorap01/coreos-tenant/fedora-coreos/devstreams)

##### EnterpriseContractPolicy

Defines the compliance policy for validating builds. The policy uses the
[Red Hat release policy](https://conforma.dev/docs/policy/release_policy.html#redhat)
from Conforma with FCOS-specific rule data.

The policy rule data is maintained in a dedicated repository with minimal baseline
rules as a starting point.

- **File**: [`ec-policy.yaml`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/blob/main/cluster/kfluxfedorap01/coreos-tenant/fedora-coreos/ec-policy.yaml)
- **Policy Data**: [`coreos/conforma-policy`](https://github.com/coreos/conforma-policy) (see [`data/rule_data.yml`](https://github.com/coreos/conforma-policy/blob/main/data/rule_data.yml))

> [!NOTE]
> The `IntegrationTestScenario` that runs Enterprise Contract validation
> is currently optional. Compliance rules are minimal and serve as a foundation
> for future expansion.

##### ReleasePlan

Configures the release process for each stream, including:
- Triggering the Jenkins pipeline for downstream processing
- Pushing images to the devel registry ([`quay.io/coreos-devel/fedora-coreos`](https://quay.io/repository/coreos-devel/fedora-coreos))

Uses Kustomize overlays for stream-specific configurations (tags, parameters).

- **Files**: [`releaseplans/`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/cluster/kfluxfedorap01/coreos-tenant/fedora-coreos/releaseplans)
- **Base ReleasePlan**: [`releaseplans/base/releaseplan.yaml`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/blob/main/cluster/kfluxfedorap01/coreos-tenant/fedora-coreos/releaseplans/base/releaseplan.yaml)

### Pipeline Configuration

The Pipelines-as-Code (PAC) definitions are stored alongside the FCOS configuration in the
[fedora-coreos-config](https://github.com/coreos/fedora-coreos-config) repository.

**Location**: [`.tekton/`](https://github.com/coreos/fedora-coreos-config/tree/testing-devel/.tekton)

**Base PipelineRun**: [`base/base/fedora-coreos.yaml`](https://github.com/coreos/fedora-coreos-config/blob/testing-devel/.tekton/base/base/fedora-coreos.yaml)

> [!TIP]
> `PipelineRun` resources are Pipelines-as-Code (PAC) components that define
> how and when pipelines are triggered. The actual `Pipeline` and `Task` definitions
> come from the Tekton component (see [Tekton Bundles](#tekton-bundles) below).

#### Tekton Bundles

The Tekton `Task` and `Pipeline` definitions are sourced from the
[bootc tekton-catalog](https://gitlab.com/fedora/bootc/tekton-catalog/) repository. We reuse
those definitions from fedora-bootc as upstream, rather than maintaining
our own definitions.

For example, the FCOS build uses the
[`buildah-build-bootc-multi-platform-oci-ta`](https://gitlab.com/fedora/bootc/tekton-catalog/-/tree/main/pipelines/buildah-build-bootc-multi-platform-oci-ta)
pipeline.

These Tekton artifacts (bundles) are built and pushed to
[quay.io/bootc-devel](https://quay.io/organization/bootc-devel).

#### Directory Structure

```
.tekton/
├── README.md
├── trigger                         # Trigger file (see "Trigger File Mechanism" below)
├── trigger-file-comment
├── base/                           # Base templates (shared across streams)
│   ├── kustomization.yaml
│   ├── base/
│   │   └── fedora-coreos.yaml     # Main PipelineRun template
│   ├── on-push/
│   │   └── kustomization.yaml     # Push event overlay
│   ├── on-pull-request/
│   │   └── kustomization.yaml     # PR event overlay
│   └── on-pull-request-overrides/
│       └── kustomization.yaml     # PR overrides overlay
├── testing-devel/                  # Stream-specific configurations
│   ├── on-push/
│   │   └── kustomization.yaml
│   └── on-pull-request/
│       └── kustomization.yaml
├── testing/
├── stable/
├── next/
├── next-devel/
├── branched/
└── rawhide/
```

#### Templating Approach

We use **Kustomize** to template PipelineRun definitions from base configurations.
This approach provides:

- **Base configuration**: Common pipeline definition in
  [`base/base/fedora-coreos.yaml`](https://github.com/coreos/fedora-coreos-config/blob/testing-devel/.tekton/base/base/fedora-coreos.yaml)
- **Event overlays**: Customizations for `on-push` and `on-pull-request` events
- **Stream overlays**: Branch-specific configurations with CEL expressions for
  targeting the correct branch

> [!NOTE]
> We may migrate to a more capable templating tool e.g: Jinja in the
> future for better flexibility and maintainability.

#### Generating Pipeline Files

After modifying base templates or kustomization files, regenerate the final
`PipelineRun` configurations using:

```bash
./ci/generate-tekton-pipelinerun
```

**Prerequisites**: [kustomize](https://github.com/kubernetes-sigs/kustomize/releases/)
must be installed.

- **Script**: [`ci/generate-tekton-pipelinerun`](https://github.com/coreos/fedora-coreos-config/blob/testing-devel/ci/generate-tekton-pipelinerun)

#### Trigger File Mechanism

Konflux is commit-based, meaning a new build is only triggered when changes are
committed. Unlike the current Jenkins setup where a daily cron job triggers builds
unconditionally, Konflux only builds when there are actual changes in the repo.

The **trigger file** (`.tekton/trigger`) serves as a mechanism to consolidate changes
and ensure at most one build per day for mechanical streams (`rawhide`, `testing-devel`):

1. **Lockfile updates**: The `bump-lockfile` job updates the trigger file with a
   timestamp whenever new packages are pulled from Fedora repositories.
2. **Config changes**: The `config-bot` updates the trigger file when propagating
   configuration changes from the main branch (`testing-devel`) to streams that
   inherit from it. Note that `testing-devel` itself does not have this property
   since changes are committed directly to it; only lockfile bumps update the
   trigger file on this branch.
3. **CEL expression filtering**: Konflux pipelines are configured (via `on-cel-expression`)
   to only build when the trigger file is modified.
4. **Autorelease**: When a new container image is built, the Konflux release pipeline
   is automatically triggered, which in turn triggers the Jenkins build job.

This approach ensures that a new FCOS build is triggered **only** when there is
a new package set OR new configuration changes, avoiding unnecessary builds compared
to the current Jenkins cron-based approach.

> [!IMPORTANT]
> Manual pipeline triggering is currently not possible due to a known bug
> tracked in [KFLUXSPRT-6050](https://issues.redhat.com/browse/KFLUXSPRT-6050).
> This capability will be available once the issue is resolved.

#### Retriggering Failed Pipelines

If a pipeline fails, you can retrigger it using the `/retest` comment:

| Scenario | How to retrigger |
|----------|------------------|
| **Pull Request** | Add a `/retest` comment on the PR |
| **Merged commit (main branch `testing-devel`)** | Add a `/retest` comment on the commit |
| **Merged commit (other branches)** | Add a `/retest branch:<branch-name>` comment on the commit (e.g., `/retest branch:rawhide` on [76896b7](https://github.com/coreos/fedora-coreos-config/commit/76896b7fc55efca9743a05797692b6695b66c3e5)) |

For more details, see the [Konflux documentation on triggering builds](https://konflux-ci.dev/docs/building/running/#triggering-a-post-merge-build).

### Hermetic Builds & RPM Lockfiles

FCOS builds in Konflux are **hermetic**, meaning all dependencies are pre-fetched
and the build environment has no network access. This enhances supply chain security.
Konflux uses [Hermeto](https://github.com/hermetoproject/hermeto) behind the scenes
to manage hermetic builds.

> [!IMPORTANT]
> While hermetic builds provide a foundation for reproducibility, FCOS builds
> are not fully reproducible yet. This remains an area for future improvement.

For RPM dependencies, we derive the `rpms.lock.yaml` file (consumed by Konflux)
from our existing rpm-ostree manifest lockfiles (e.g.,
[`manifest-lock.x86_64.json`](https://github.com/coreos/fedora-coreos-config/blob/testing-devel/manifest-lock.x86_64.json)).

This derivation is performed by the [`prepare-build-context`](https://gitlab.com/fedora/bootc/tekton-catalog/-/tree/main/tasks/prepare-build-context)
Tekton task, which runs at the beginning of the pipeline immediately after cloning
the repository.

- **Implementation details**: [fedora-coreos-tracker#2049](https://github.com/coreos/fedora-coreos-tracker/issues/2049)

> [!NOTE]
> We may re-evaluate the RPM lockfile approach once the DNF5 manifest plugin
> is available. This plugin will provide native lockfile generation capabilities.

### SBOMs and Attestations

SBOMs (Software Bill of Materials) and build attestations are **not yet published**
to the final registry ([`quay.io/fedora/fedora-coreos`](https://quay.io/repository/fedora/fedora-coreos)). They are currently available
in the devel registry ([`quay.io/coreos-devel/fedora-coreos`](https://quay.io/repository/coreos-devel/fedora-coreos)).

**Reasons**:

1. **Jenkins-based releases**: In Phase 1, releases are handled by Jenkins, which
   does not propagate the associated artifacts (SBOMs, attestations, signatures).
   Phase 4 will address this when Konflux handles the full release process.

2. **Registry clutter**: The current Cosign tag-based conventions create multiple
   tags per image (`.sbom`, `.att`, `.sig` suffixes), making the registry difficult
   to browse. We are waiting for native [OCI 1.1 Referrers API](https://opencontainers.org/posts/blog/2024-03-13-image-and-distribution-1-1/#referrers-api)
   support in Konflux/Cosign/Tekton Chains for a cleaner solution.

For more details, see the comment [fedora-coreos-tracker#2031 (comment)](https://github.com/coreos/fedora-coreos-tracker/issues/2031#issuecomment-3675105908).

### References

- **Migration Tracker**: [fedora-coreos-tracker#2031](https://github.com/coreos/fedora-coreos-tracker/issues/2031)
- **Konflux Documentation**: [konflux-ci.dev](https://konflux-ci.dev/docs/)
- **Conforma Policy Documentation**: [conforma.dev](https://conforma.dev/docs/policy/release_policy.html)
- **Tenant Configuration**: [GitLab tenants-config](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/cluster/kfluxfedorap01/coreos-tenant/fedora-coreos)
- **Pipeline Configuration**: [GitHub .tekton/](https://github.com/coreos/fedora-coreos-config/tree/testing-devel/.tekton)
