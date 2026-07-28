# Triage Workflow

When triaging a pipeline failure (whether triggered by a scheduled task or a user request), follow these stages in order. Complete all stages before presenting results.

## Stage 1 -- Gather

Collect build metadata using Jenkins MCP tools:
- Call `jenkins_getBuild` with the job name and build number
- Call `jenkins_getJob` for the job's health report and recent build history

Record:
- Job name, build number, result, URL
- Stream and architecture (from build parameters)
- Trigger cause (upstream job, timer, user)
- Duration and timestamp
- For `build` job failures: identify which `build-arch` child failed (check trigger causes in recent `build-arch` failures)

## Stage 2 -- Logs

Pull console log and extract key evidence:
- Call `jenkins_getBuildLog` to retrieve the console log (use pagination for large logs)
- Call `jenkins_searchBuildLog` with error patterns: `error:`, `FATAL:`, `failed to`, `timeout`, `unauthorized`, `FAILED`
- Call `jenkins_getTestResults` to check for kola test failures

For `build-node-image` failures, also search for:
- Versionlock conflicts: `is filtered out by exclude filtering`
- DNF dependency errors: `requires`, `nothing provides`
- Extensions build failures: `extensions-container`

## Stage 3 -- Classify

Map the failure to a single primary category from the failure taxonomy (see failure patterns instructions). Assign a confidence level:
- *high* -- Clear evidence points to one root cause
- *medium* -- Evidence suggests a cause but is not conclusive
- *low* -- Multiple possible causes, limited evidence

Formulate a ROOT_CAUSE label (under 60 characters, consistent wording for the same underlying issue).

## Stage 4 -- Summarize

Produce a structured triage summary:

```
*Triage: <job> #<build>*

*ROOT_CAUSE:* <short description>
*Classification:* <category> (confidence: <level>)
*Stream:* <stream> | *Arch:* <arch>
*Build:* <jenkins URL>

*Evidence:*
- <key log lines or test results>
- <package diff if relevant>

*Suggested next steps:*
- <action 1>
- <action 2>
```

## Finding Last Known Good Build

To compare against the last successful build for the same stream:
1. Call `jenkins_getJobs` or `jenkins_getJob` for the job
2. Look at recent builds, filter by SUCCESS status
3. Match the stream from build parameters/description
4. Skip builds with "no new build" in the description
5. Use `jenkins_getBuildChangeSets` to compare SCM changes between good and bad builds

## Package Comparison

When a package change is suspected:
1. Use `jenkins_getBuildLog` on both good and bad builds
2. Search for `Adding versionlock on:` lines or the lines right after `ostree diff commit from` to identify package versions
3. Compare package versions between the two builds
4. Search for the changed package name in the bad build's error output

For coreos-assembler version changes:
1. Check build artifacts for `coreos-assembler-git.json`
2. Compare cosa commit SHAs between good and bad builds
3. Use GitHub to review commits between the two SHAs
