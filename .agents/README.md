The files here are instruction files for two Chai Bot (https://github.com/redhat-chai-bot)
personas for the CoreOS team:

- coreos_pipeline: monitors the RHCOS Jenkins pipeline, triages build failures, and tracks issues in Jira (COS project). Serves #jenkins-rhcos-art.
- coreos_internal: general-purpose CoreOS engineering assistant covering RHCOS architecture, builds, CVEs, and team processes. Serves #dev-coreos and #forum-rhel-coreos.

While they are intended to be used with Chai Bot they can be used as
context/instructions for any LLM/Agent harness. Try it out!

Files are organized as:
- Persona domain instructions (always loaded by the persona)
- Scheduled task prompts (cron-driven pipeline monitoring)

These files will be consumed by ship-help-bot via %include() directives.

Content adapted from existing triage workflows and domain knowledge in:
- https://github.com/cverna/coreos-agent-tools (main branch)
- https://github.com/suppathak/coreos-agent-tools (feature/pipeline-triage-workflow branch)
