---
description: "Produce a plan for an upgrade, migration, CVE fix, or coverage task (read-only)"
agent: planner
argument-hint: "task, module, target - e.g. 'upgrade order-service to Java 21'"
---
Plan this task: ${input:task:describe the task, module and target}

Follow your preflight budget. Output only the plan in your standard structure, then stop.
