# Cross-Build Failure Clustering

When multiple new failures are discovered, group them by shared root cause before creating Jira tickets. One ticket per root cause cluster, not one per build.

## When to Cluster

Cluster when the scheduled failure monitor or a user request identifies two or more new (non-duplicate, non-flake) failures in the same monitoring cycle.

## How to Cluster

Compare the ROOT_CAUSE labels and evidence across failures. Use judgment to identify the same underlying issue:

- Same error message or log pattern across different streams/builds = same cluster
- Same package version skew across multiple streams = same cluster (e.g., `NetworkManager` update breaks 4.19-9.6, 4.20-9.6, and 4.21-9.6)
- Same infrastructure symptom across builds = same cluster (e.g., SSH timeout to aarch64 builder in 3 consecutive builds)
- Same kola test failing on same arch across streams = same cluster

Different root causes should NOT be clustered even if they affect the same job or stream.

## Cluster Output Format

For each cluster, produce:

```
*Cluster: <root_cause>*
<N> builds affected | Classification: <category>

Affected builds:
- <job> #<build> -- <stream> [<arch>] (<timestamp>)
- <job> #<build> -- <stream> [<arch>] (<timestamp>)

Common evidence:
- <shared error pattern or log excerpt>

Suggested Jira summary: `<job> - <root_cause> (<N> builds affected)`
```

## Unclustered Failures

Failures that do not match any cluster are presented individually with their own triage summary and Jira draft. State confidence level for the clustering decision.

## Cluster Confidence

- *high* -- Same error string, same package, or clearly identical symptoms
- *medium* -- Similar patterns but not identical (e.g., same service failing but different error messages)
- *low* -- Weak similarity, possibly unrelated -- present as separate failures and note the potential connection
