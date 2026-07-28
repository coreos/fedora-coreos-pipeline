# Scheduled Task: RHCOS Pipeline Failure Monitor

You are running a scheduled task that detects, triages, and reports new
pipeline failures. This task runs every 30 minutes. Only post if there are
new findings. Call `no_action_required()` if nothing new is found.

## Goal

Find new pipeline failures, triage them automatically, cluster related
failures by root cause, and post a summary with Jira drafts for human
approval. Auto-close resolved failures.

## Procedure

### Step 1 -- Discovery

Query Jenkins for recent failures across key jobs. Process `build-arch`
failures BEFORE `build` failures so that parent `build` numbers are
collected first and skipped during the `build` pass (see steps 3-5).

1. Call `jenkins_getJobs` to list all jobs
2. For `build-arch`: call `jenkins_getJob` and examine recent FAILURE builds
3. For each `build-arch` failure, identify its parent `build` number (from
   the trigger cause in `jenkins_getBuild`)
4. Collect these parent build numbers -- they will be skipped in step 5
5. For `build`, `build-node-image`, `release`: examine recent FAILURE builds.
   Skip any `build` whose number was collected in step 4 (the real failure is
   in the child `build-arch` job)

### Step 2 -- Deduplication

Check each discovered failure against existing COS Jira subtasks.

1. Find the current week's Pipeline Monitoring parent task:
   `project = COS AND type = Task AND summary ~ "Pipeline Monitoring" ORDER BY created DESC`
2. Query its subtasks using the Jira tool
3. For each Jenkins failure, run 3-pass matching:
   - *Pass 1 -- Exact match:* same job name AND build number in a subtask summary. Skip.
   - *Pass 2 -- Related issue:* same job + stream + arch with similar error pattern. Note for comment instead of new ticket.
   - *Pass 3 -- Semantic match:* different job/stream but clearly same root cause. Note for linking.
4. Only failures returning no match proceed to triage

### Step 3 -- Auto-Close Resolved Failures

For each OPEN subtask under the current week's parent:

1. Parse the subtask summary to extract job, build number, stream, and arch
2. Call `jenkins_getJob` or `jenkins_getBuild` for recent SUCCESS builds
3. For `build-arch`: check if a SUCCESS build with higher build number exists
   for the same stream AND architecture
4. For `build` / `build-node-image`: check stream match only
5. If a later successful build exists, close the subtask with a comment:
   "Auto-closed: successful build `<job>` #N for stream `<stream>` confirms
   this failure was transient."

### Step 4 -- Check Verified Knowledge

For each new (non-duplicate) failure, check verified knowledge for known
flake patterns. If a VK lesson matches the failure pattern (e.g., "known
flaky test on ppc64le"), note it as a known flake and skip deep triage.

### Step 5 -- Triage New Failures

For each failure that is not a duplicate and not a known flake, perform
full triage using the triage workflow from your domain instructions:

1. *Gather:* Call `jenkins_getBuild` for metadata (parameters, trigger,
   duration, result)
2. *Logs:* Call `jenkins_getBuildLog` and `jenkins_searchBuildLog` with
   error patterns (`error:`, `FATAL:`, `timeout`, `unauthorized`,
   `is filtered out by exclude filtering`)
3. *Test results:* Call `jenkins_getTestResults` to check for kola failures.
   Interpret `rerun_failed` per failure patterns instructions.
4. *Classify:* Assign a primary failure category and confidence level
5. *Summarize:* Produce a ROOT_CAUSE label and one-line summary

### Step 6 -- Cluster by Root Cause

If multiple new failures were triaged, group them by ROOT_CAUSE per the
clustering instructions. Same underlying issue across different
streams/builds = one cluster = one Jira draft.

### Step 7 -- Format and Post

Post a Slack `mrkdwn` summary to the channel. Use the following structure:

```
*Pipeline Failure Monitor*

*Summary:* X failures found | Y already tracked | Z auto-closed | W known flakes | N new

*Auto-closed:*
- COS-1234 (superseded by `build-arch` #4360)

*Known flakes:*
- `build-arch` #4358 `rhel-9.6` [s390x] -- known flaky: <VK lesson summary>

*New failures:*

*Cluster 1: <root_cause>* (N builds)
Classification: <category> | Confidence: <level>
- `<job>` #<build> -- `<stream>` [<arch>]
- `<job>` #<build> -- `<stream>` [<arch>]
Evidence: <key error excerpt>
Suggested Jira: `<job> - <root_cause> (N builds affected)`

*Unclustered:*
- `<job>` #<build> -- `<stream>` [<arch>] -- <ROOT_CAUSE>
  Classification: <category> | Evidence: <short excerpt>
  Suggested Jira: `<job> #<build> - <stream> [<arch>] <description>`
```

If there are detailed triage findings, use `---THREAD_DETAILS---` to post
per-failure analysis as threaded replies.

### Step 8 -- Gate

Do NOT create Jira issues or trigger Jenkins builds. Present the summary
with suggested Jira text and wait for human approval in the thread. Per the
triage gate instructions, all write actions require explicit human approval.

### Step 9 -- Nothing New

If all discovered failures are duplicates, known flakes, or were
auto-closed, and no new failures need triage, call `no_action_required()`
and stop.

## Constraints

- Keep the top-level message concise (under 3000 characters). Use threaded
  replies for detailed per-failure analysis.
- Do not use `<!channel>` or broad mentions.
- Do not speculate about root causes beyond what the evidence supports.
- If Jenkins MCP tools are unavailable or timing out, report the tooling
  issue and stop -- do not fabricate build data.
- When proposing a VK lesson for a new flake pattern, follow the
  self-learning guidelines (durable, factual knowledge only).
