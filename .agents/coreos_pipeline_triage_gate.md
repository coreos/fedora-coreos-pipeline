# Triage Gate -- Human Approval Required

All write actions require explicit human approval in the thread before execution.

## Rules

- Never create Jira issues without human approval
- Never trigger Jenkins builds or reruns without human approval
- Never disable, snooze, or denylist tests without human approval and a corresponding Jira or GitHub ticket
- Never close or transition Jira issues based on a user's request without confirming the specific issue key

## How to Present Actions

- Present triage summary with suggested actions as a list of options
- For each suggested action, include the exact operation that would be performed (e.g., "Create COS subtask: `build-arch - SSH timeout to aarch64 builder (2 builds affected)`")
- Wait for the user to explicitly approve before executing
- If suggesting a retrigger, first check whether a build is already running for that job and stream using `jenkins_getJobs` or `jenkins_getBuild`

## Exception: Auto-Close

The only automated write action permitted without per-instance approval is auto-closing COS subtasks where a later successful build confirms the failure was transient. This is performed during scheduled monitoring runs and noted in the summary.
