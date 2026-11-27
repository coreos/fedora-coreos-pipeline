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
                      CURRENT WORKFLOW (Jenkins as orchestrator)
══════════════════════════════════════════════════════════════════════════════════════

 JENKINS                                   KONFLUX
────────────────────────────────────────────────────────────────────────────────────────
    │                                         │
 ┌──┴───────────────────┐                     │
 │ build job            │   look up a         │
 │ • Build in Konflux   │   validated         │
 │                      │   Snapshot for      │
 │                      │   the commit        │
 │                      │────────────────────►│
 │                      │                     │
 │                      │   if none:          │
 │                      │   trigger-pac-build │
 │                      │────────────────────►│
 │                      │                ┌────┴─────────────────┐
 │                      │                │     PipelineRun      │
 │                      │                │  • clone-repository  │
 │                      │                │  • prepare-build-ctx │
 │                      │                │  • prefetch-deps     │
 │                      │                │  • multi-arch build  │
 │                      │                │  • etc               │
 │                      │                └────┬─────────────────┘
 │                      │                     │  pushed to
 │                      │                     ▼
 │                      │          ┌─────────────────────┐
 │                      │          │ quay.io/konflux-    │
 │                      │          │ fedora/coreos-tenant│
 │                      │          │ (intermediate)      │
 │                      │          └──────────┬──────────┘
 │                      │                ┌────┴────┐
 │                      │                │   ITS   │
 │                      │                └────┬────┘
 │                      │   poll Snapshot     │
 │                      │◄────────────────────│
 │ • Import (cosa)      │                     │
 │ • Build disk images  │                     │
 │ • Test               │                     │
 └──┬───────────────────┘                     │
    │                                         │
 ┌──┴───────────────────┐   create Release    │
 │ release job          │   (RHCOS only)      │
 │ • Konflux Release    │────────────────────►│
 │   (RHCOS only)       │                ┌────┴────────┐
 │                      │   poll status  │ ReleasePlan │
 │                      │◄───────────────└────┬────────┘
 │                      │                     │
 │                      │                     ▼
 │                      │          ┌─────────────────────┐
 │                      │          │ ReleasePlanAdmission│
 │                      │          │ (RH Release         │
 │                      │          │ Engineering tenant) │
 │                      │          └─────────────────────┘
 │ • Release            │
 └──┬───────────────────┘
    │  pushed to
    ▼
 ┌─────────────────────┐
 │ quay.io/fedora/     │
 │ fedora-coreos       │
 │ (final)             │
 └─────────────────────┘
```

See [Jenkins and Konflux Integration](#jenkins-and-konflux-integration) for details.

#### Container Registries

| Registry | Purpose |
|----------|---------|
| [`quay.io/konflux-fedora/coreos-tenant/`](https://quay.io/organization/konflux-fedora?tab=repositories&q=coreos-tenant) | **Intermediate registry** - Stores all artifacts built by Tekton pipelines. Created via the `ImageRepository` CRD defined in the [ProjectDevelopmentStreamTemplate](#projectdevelopmentstreamtemplate). Jenkins imports the container image from here. |
| [`quay.io/coreos-devel/fedora-coreos`](https://quay.io/repository/coreos-devel/fedora-coreos) | **Devel registry** - Stores images released by Konflux through the ReleasePlan. No longer updated for FCOS since automatic release has been disabled (see [ReleasePlan](#releaseplan)). |
| [`quay.io/fedora/fedora-coreos`](https://quay.io/repository/fedora/fedora-coreos) | **Final registry** - Official public registry for end users. Images are pushed here by Jenkins after successful release. |


> [!NOTE]
> The images that were published in the devel registry (`coreos-devel/fedora-coreos`) when automatic
> release was enabled are exactly identical to the ones in the final registry (`fedora/fedora-coreos`).
> The difference is that the final registry images are the official artifacts
> pushed by Jenkins as part of the release process. The devel registry repository is expected to be deprecated
> once Konflux takes over the release responsibility (Phase 4).

### Tenant Infrastructure Configuration

The Konflux tenant configuration for FCOS is where all Konflux-specific OpenShift CRDs
are defined in GitOps mode. The configuration is stored in the
[tenants-config](https://gitlab.com/fedora/infrastructure/konflux/tenants-config) repository.

**Location**: [`clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos/`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos)

> [!NOTE]
> The `cluster/kfluxfedorap01/` directory is deprecated since the installation
> of the new `kflux-fedora-01` cluster. All changes must go to
> `clusters/kflux-fedora-01/`.

#### Fedora Konflux Cluster

The Fedora Konflux instance runs on the `kflux-fedora-01` ROSA (Red Hat OpenShift on AWS) cluster. Key characteristics:

- **Konflux components**: Deployed from [redhat-appstudio/infra-deployments](https://github.com/redhat-appstudio/infra-deployments)
- **Authentication**: Integrated with Fedora Account System (FAS)
- **Multi-architecture support**: Provisioned for `x86_64`, `aarch64`, `ppc64le`, and `s390x`

For more details (web UI, OpenShift console, ArgoCD, etc.), see the
[infra-deployments README](https://github.com/redhat-appstudio/infra-deployments).

#### Directory Structure

```
coreos-tenant/
├── rbac/                           # RBAC, incl. the Jenkins ServiceAccount
│   └── rbac.yaml
└── fedora-coreos/
    ├── kustomization.yaml
    ├── project.yaml                # Project definition
    ├── template.yaml               # ProjectDevelopmentStreamTemplate
    ├── ec-policies/                # EnterpriseContractPolicy
    │   ├── kustomization.yaml
    │   ├── development/
    │   └── production/
    ├── devstreams/                 # ProjectDevelopmentStream definitions
    │   ├── kustomization.yaml
    │   ├── testing-devel.yaml
    │   ├── testing.yaml
    │   ├── stable.yaml
    │   ├── next.yaml
    │   ├── next-devel.yaml
    │   ├── branched.yaml
    │   └── rawhide.yaml
    └── releaseplans/               # ReleasePlan configurations
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

- **File**: [`project.yaml`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/blob/main/clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos/project.yaml)

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

- **File**: [`template.yaml`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/blob/main/clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos/template.yaml)

> [!NOTE]
> The `ReleasePlan` CRD is not currently supported in the `ProjectDevelopmentStreamTemplate`.
> This is why ReleasePlans are managed separately using Kustomize overlays in the
> [`releaseplans/`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos/releaseplans) directory.

##### ProjectDevelopmentStream

Each FCOS stream has its own `ProjectDevelopmentStream` that references the template
with the appropriate version/branch value.

**Configured streams**: `testing-devel`, `testing`, `stable`, `next`, `next-devel`,
`branched`, `rawhide`

- **Files**: [`devstreams/`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos/devstreams)

##### EnterpriseContractPolicy

Defines the compliance policy for validating builds. The policy uses the
[Red Hat release policy](https://conforma.dev/docs/policy/release_policy.html#redhat)
from Conforma with FCOS-specific rule data.

The policy rule data is maintained in a dedicated repository with minimal baseline
rules as a starting point.

There are two policies:

| Policy | Description |
|--------|-------------|
| `fedora-coreos-ec-policy-production` | Base policy |
| `fedora-coreos-ec-policy-development` | Kustomize overlay of the production policy that also excludes the `rpm_signature.rule_data_provided` rule |

- **Files**: [`ec-policies/`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos/ec-policies)
- **Policy Data**: [`coreos/conforma-policy`](https://github.com/coreos/conforma-policy) (see [`data/rule_data.yml`](https://github.com/coreos/conforma-policy/blob/main/data/rule_data.yml))

> [!NOTE]
> The `IntegrationTestScenario` that runs Enterprise Contract validation
> is currently optional. Compliance rules are minimal and serve as a foundation
> for future expansion.

##### ReleasePlan

Configures the release process for each stream (`release-fedora-coreos-<stream>`),
which pushes images to the devel registry
([`quay.io/coreos-devel/fedora-coreos`](https://quay.io/repository/coreos-devel/fedora-coreos)).

Automatic release is disabled (`release.appstudio.openshift.io/auto-release: 'false'`),
so the ReleasePlans are currently not used for FCOS: they no longer trigger Jenkins,
and the Jenkins `release` job does not trigger them either (see [Release Flow](#release-flow)).
The ReleasePlan definitions are kept so they can be reused once the whole pipeline
moves to Konflux.

Uses Kustomize overlays for stream-specific configurations (tags, parameters).

- **Files**: [`releaseplans/`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos/releaseplans)
- **Base ReleasePlan**: [`releaseplans/base/releaseplan.yaml`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/blob/main/clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos/releaseplans/base/releaseplan.yaml)

### Pipeline Configuration

The Pipelines-as-Code (PAC) definitions are stored alongside the FCOS configuration in the
[fedora-coreos-config](https://github.com/coreos/fedora-coreos-config) repository.

**Location**: [`.tekton/`](https://github.com/coreos/fedora-coreos-config/tree/testing-devel/.tekton)

**Jinja2 Template**: [`templates/pipelinerun.yaml.j2`](https://github.com/coreos/fedora-coreos-config/blob/testing-devel/.tekton/templates/pipelinerun.yaml.j2)

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
├── templates/                      # Jinja2 templates and configuration
│   ├── pipelinerun.yaml.j2        # Jinja2 template for PipelineRun
│   └── streams.yaml               # Stream definitions and settings
├── testing-devel/                  # Generated PipelineRun files
│   ├── on-push/
│   │   └── fedora-coreos-testing-devel-on-push.yaml
│   ├── on-pull-request/
│   │   └── fedora-coreos-testing-devel-on-pull-request.yaml
│   └── on-pull-request-overrides/
│       └── fedora-coreos-testing-devel-on-pull-request-overrides.yaml
├── testing/
├── stable/
├── next/
├── next-devel/
├── branched/
└── rawhide/
```

#### Templating Approach

We use **Jinja2** templates with a Python script to generate PipelineRun definitions.
This approach provides:

- **Jinja2 template**: Common pipeline definition in
  [`templates/pipelinerun.yaml.j2`](https://github.com/coreos/fedora-coreos-config/blob/testing-devel/.tekton/templates/pipelinerun.yaml.j2)
- **Stream configuration**: All streams and their settings defined in
  [`templates/streams.yaml`](https://github.com/coreos/fedora-coreos-config/blob/testing-devel/.tekton/templates/streams.yaml)
- **Event types**: Each stream generates `on-push` and `on-pull-request` pipelines,
  with an additional `on-pull-request-overrides` for streams with override lockfiles

#### Generating Pipeline Files

After modifying Jinja2 templates or stream configuration, regenerate the
`PipelineRun` files using:

```bash
./ci/generate-tekton-pipelinerun.py
```

To verify that generated files are up to date (useful for CI):

```bash
./ci/generate-tekton-pipelinerun.py --check
```

**Prerequisites**: Python 3.6+, `jinja2`, and `pyyaml` must be installed
(`pip install jinja2 pyyaml`).

- **Script**: [`ci/generate-tekton-pipelinerun.py`](https://github.com/coreos/fedora-coreos-config/blob/testing-devel/ci/generate-tekton-pipelinerun.py)

#### Build Triggering

The `on-push` PipelineRuns are **not** triggered by merged commits: they are
configured with `pipelinesascode.tekton.dev/on-cel-expression: "false"`.
Instead, the Jenkins `build` job triggers them on demand (see
[Jenkins and Konflux Integration](#jenkins-and-konflux-integration)).

The `on-pull-request` PipelineRuns are still triggered by Pipelines-as-Code on
pull requests.

#### Retriggering Failed Pipelines

| Scenario | How to retrigger |
|----------|------------------|
| **Pull Request** | Add a `/retest` comment on the PR |
| **Branch build** | Re-run the Jenkins `build` job for the stream |

For more details, see the [Konflux documentation on triggering builds](https://konflux-ci.dev/docs/building/running/).

### Jenkins and Konflux Integration

As long as both Jenkins and Konflux are part of the pipeline, **Jenkins is the
orchestrator**: it triggers the Konflux container build, waits for the result,
builds the disk images, runs the tests, and finally releases the build.

This replaces the previous push-based model, where every Konflux build triggered
the Jenkins `build` job through the ReleasePlan. Having a single orchestrator:

- avoids building container images that are never used,
- avoids delays between the Konflux build and the rest of the pipeline,
- keeps Jenkins as the single interface to manually start a build,
- avoids ping-pong and duplicated failure notifications between both systems.

We will evaluate later whether Konflux should become the orchestrator.

The design discussion took place in this thread [fedora-coreos-tracker#2241](https://github.com/coreos/fedora-coreos-tracker/issues/2241).

#### Build Flow

When `build_in_konflux` is enabled for a stream, the `Build in Konflux` stage
of the [`build`](../jobs/build.Jenkinsfile) job:

1. Looks for an existing **validated** Snapshot (`AppStudioTestSucceeded: True`)
   for the component and the source config commit. If one exists, its container
   image is reused and no new build is triggered.
2. Otherwise, triggers a Konflux build by annotating the component with
   `build.appstudio.openshift.io/request=trigger-pac-build`, which runs the
   `on-push` PipelineRun.
3. Polls the PipelineRun and the resulting Snapshot until it is validated.
   Note: PipelineRuns queued while Konflux waits for resources are reported as pending.
4. If the commit built by Konflux differs from the one used for `cosa init`,
   re-runs `cosa init` with the Konflux commit.

The `Import Konflux image` stage then imports the container image with
`cosa import`, and the rest of the pipeline (disk images, tests, multi-arch
jobs) runs as usual. The Snapshot name is passed to the `release` job.

#### Release Flow

The [`release`](../jobs/release.Jenkinsfile) job publishes the container image
to the final registry ([`quay.io/fedora/fedora-coreos`](https://quay.io/repository/fedora/fedora-coreos))
as usual.

In addition, when the `KONFLUX_SNAPSHOT` parameter is set, its `Konflux Release` stage:

1. Fetches the Snapshot and checks that the container image version
   (`org.opencontainers.image.version` label) matches the `VERSION` parameter.
2. Creates a `Release` resource for the ReleasePlan named after the Snapshot's
   application (e.g. `fedora-coreos-rawhide`). The stage is skipped if that
   ReleasePlan does not exist.
3. Polls the `Release` until it succeeds or fails (10 minutes timeout).

> [!NOTE]
> This stage is currently **not active for FCOS**: the FCOS ReleasePlans are named
> `release-fedora-coreos-<stream>`, so no ReleasePlan matches the application name
> and no Konflux `Release` is created.
> It is **active for RHCOS**, where the stream's ReleasePlan (e.g. `rhel-coreos-10-2`)
> triggers a `ReleasePlanAdmission` in the Red Hat Release Engineering tenant.

#### Configuration

The integration is configured in [`config.yaml`](../config.yaml) (see
[`docs/config.yaml`](config.yaml) for all options):

| Option | Level | Description |
|--------|-------|-------------|
| `build_in_konflux` | stream | Build the container image in Konflux instead of Jenkins |
| `konflux_application` | stream | Konflux application/component name of the stream (e.g. `fedora-coreos-rawhide`) |
| `konflux.api_url` | top-level | Konflux cluster API URL |
| `konflux.namespace` | top-level | Konflux tenant namespace (`coreos-tenant`) |
| `konflux.token_credential` | top-level | Jenkins credential ID holding the Konflux API token |
| `konflux.proxy_domain` | top-level | Optional proxy used to pull images from the intermediate registry |

#### Service Account

Jenkins authenticates to Konflux with the `konflux-bot-0` ServiceAccount, defined in
[`rbac/rbac.yaml`](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/blob/main/clusters/kflux-fedora-01/tenants/coreos-tenant/rbac/rbac.yaml).
It is bound to the `konflux-builder-bot-actions` and `konflux-releaser-bot-actions`
ClusterRoles, so that it can trigger builds, read Snapshots and create Releases.
Its token is stored as a secret in the Jenkins instance (`konflux-bot-0` credential).

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
to the final registry ([`quay.io/fedora/fedora-coreos`](https://quay.io/repository/fedora/fedora-coreos)). They are only available
in the devel registry ([`quay.io/coreos-devel/fedora-coreos`](https://quay.io/repository/coreos-devel/fedora-coreos))
for the images published there while automatic release was enabled.

**Reasons**:

1. **Jenkins-based releases**: Releases to the final registry are handled by Jenkins,
   which does not propagate the associated artifacts (SBOMs, attestations, signatures).
   Phase 4 will address this when Konflux handles the full release process.

2. **Registry clutter**: The current Cosign tag-based conventions create multiple
   tags per image (`.sbom`, `.att`, `.sig` suffixes), making the registry difficult
   to browse. We are waiting for native [OCI 1.1 Referrers API](https://opencontainers.org/posts/blog/2024-03-13-image-and-distribution-1-1/#referrers-api)
   support in Konflux/Cosign/Tekton Chains for a cleaner solution.

For more details, see the comment [fedora-coreos-tracker#2031 (comment)](https://github.com/coreos/fedora-coreos-tracker/issues/2031#issuecomment-3675105908).

### References

- **Migration Tracker**: [fedora-coreos-tracker#2031](https://github.com/coreos/fedora-coreos-tracker/issues/2031)
- **Jenkins as orchestrator**: [fedora-coreos-tracker#2241](https://github.com/coreos/fedora-coreos-tracker/issues/2241)
- **Konflux Documentation**: [konflux-ci.dev](https://konflux-ci.dev/docs/)
- **Conforma Policy Documentation**: [conforma.dev](https://conforma.dev/docs/policy/release_policy.html)
- **Tenant Configuration**: [GitLab tenants-config](https://gitlab.com/fedora/infrastructure/konflux/tenants-config/-/tree/main/clusters/kflux-fedora-01/tenants/coreos-tenant/fedora-coreos)
- **Pipeline Configuration**: [GitHub .tekton/](https://github.com/coreos/fedora-coreos-config/tree/testing-devel/.tekton)
