---
description: "Fix code-level security findings (SAST) in named classes with a failing-first test for each"
agent: security-fixer
argument-hint: "finding type + class list, or path to the SAST export"
---
Fix these code-level security findings: ${input:findings}

Follow Phase 2. For each finding: show me the proposed fix and the exploit-style test, then apply. Use existing security utilities in the module before writing new ones. Report with the standard table.
