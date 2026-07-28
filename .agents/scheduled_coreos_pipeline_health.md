# Scheduled Report: RHCOS Pipeline Daily Health

You are running a scheduled task that produces a daily summary of RHCOS
Jenkins pipeline health. Post to the channel only if there are failures or
degraded jobs. Call `no_action_required()` if everything is healthy.

## Goal

Check the health of all key pipeline jobs across all active streams. Compute
pass rates from recent builds and produce a concise summary highlighting
failures and degraded jobs.

## Procedure

### 1. Query Job Status

Call `jenkins_getJobs` to list all jobs. For each key job (`build`,
`build-arch`, `build-node-image`, `release`), call `jenkins_getJob` to get
the health report, last build result, and last successful/failed build info.

### 2. Collect Recent Build History

For each key job, call `jenkins_getBuild` for the last 10-15 builds to
compute pass rates. Record:
- Build number, result (SUCCESS/FAILURE), timestamp
- Stream (from build description or parameters)
- Skip builds still in progress

### 3. Compute Health Metrics

For each job, compute:
- Overall pass rate (last 10 completed builds)
- Per-stream pass rate (group builds by stream)
- Jobs currently in a failed state (last build result = FAILURE)

Health indicators:
- Healthy: pass rate >= 80%
- Degraded: pass rate >= 50% and < 80%
- Unhealthy: pass rate < 50%

### 4. Decide Whether to Post

- If ALL key jobs have pass rate >= 80% and no job's last build is FAILURE:
  call `no_action_required()` and stop.
- Otherwise: post the summary.

### 5. Format Response

Post a concise Slack `mrkdwn` message. Do not use markdown tables (Slack does
not render them). Use bullet lists and bold text.

```
*RHCOS Pipeline Health*

*Overall:* X/Y jobs healthy | Z failures in last 24h

*build-arch* -- 70% (7/10) :warning:
  `rhel-9.6` [ppc64le]: 2 consecutive failures (#4355, #4357)
  `rhel-9.8` [aarch64]: 1 failure (#4360)

*build-node-image* -- 90% (9/10)
  `4.21-9.6`: 1 failure (#1234)

*build* -- 100% (10/10) :white_check_mark:
*release* -- 100% (5/5) :white_check_mark:
```

Guidelines:
- Sort jobs by pass rate (worst first)
- For degraded/unhealthy jobs, list affected streams with build numbers
- Include links to failing builds when possible
- Note consecutive failures (same stream, multiple builds in a row)
- Keep the top-level message under 2000 characters
- Post without any @-mentions or `<!channel>` -- this is a status report, not an alert

## Constraints

- This task runs daily on weekday mornings. It is a health overview, not a
  triage report. Do not perform deep log analysis or root cause investigation.
- If you need to investigate a specific failure, note it as "needs triage"
  and let the failure monitor or a user-requested triage handle it.
- Do not create Jira issues from this task.
