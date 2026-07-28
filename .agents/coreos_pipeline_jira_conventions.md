# Jira Conventions for Pipeline Monitoring

## Project and Structure

- *Project:* COS (CoreOS)
- *Parent task:* A weekly "Pipeline Monitoring" task exists in COS. Find it with: `project = COS AND type = Task AND summary ~ "Pipeline Monitoring" ORDER BY created DESC`
- *Subtasks:* Each tracked failure (or failure cluster) is a subtask under the current week's parent

## Subtask Naming

*Single failure:*
`<job> #<build> - <stream> [<arch>] <brief-description>`

Examples:
- `build-arch #4357 - rhel-9.6 [ppc64le] SSH timeout to remote builder`
- `build-node-image #1234 - 4.21-9.6 DNF conflict: NetworkManager version skew`

*Failure cluster (multiple builds, same root cause):*
`<job> - <root_cause> (<N> builds affected)`

Examples:
- `build-node-image - NetworkManager version skew (2 builds affected)`
- `build-arch - SSH timeout to aarch64 builder (3 builds affected)`

Always use the full Jenkins stream name (e.g., `4.22-9.8` not `4.22`).

## Labels

Apply one of these labels to each subtask:
- `flake-infrastructure` -- transient infrastructure issues (repo timeouts, network errors, builder problems)
- `flake-test` -- flaky test failures
- `bug` -- actual bugs requiring code or config fixes

## Subtask Description Template

Include in the description:
- Build details: job, number, stream, arch, timestamp, duration, Jenkins URL
- Root cause analysis: classification, confidence, reasoning
- Evidence: key error messages in code blocks
- For clusters: table of all affected builds with URLs
- Resolution status and recommended next steps

## Deduplication Rules

Before creating a new subtask, check for existing ones under the current week's parent:

*3-pass matching:*
1. *Exact match* -- same job name AND build number already tracked. Skip.
2. *Related issue* -- same job + stream + arch with a similar error pattern. Add a comment to the existing subtask instead of creating a new one.
3. *Semantic match* -- different job or stream but clearly the same root cause (e.g., same package conflict across streams). Link to the existing subtask or add as a comment.

*Matching heuristics (weight higher when several align):*
- Same Jenkins job name appears in the ticket
- Same failure family (registry, compose, kola, infra) and similar error line
- Same stream and/or architecture
- Ticket is already a Pipeline Monitoring subtask for a similar symptom

## Auto-Close Logic

For open subtasks in the current week's parent, check if a later successful build exists:
- For `build-arch`: match stream AND architecture. If a SUCCESS build with a higher build number exists for the same stream and arch, the failure was transient.
- For `build` / `build-node-image`: match stream only (arch-agnostic).
- Close the subtask with a comment: "Auto-closed: successful build `<job>` #N for stream `<stream>` confirms this failure was transient."
