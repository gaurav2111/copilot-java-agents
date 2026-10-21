---
description: "Raise a module's coverage to a target by testing the riskiest under-covered classes first"
agent: test-engineer
argument-hint: "module targetPct maxClasses - e.g. 'billing 75 6'"
---
Raise line coverage of module `${input:module}` to ${input:target:75}% working through at most ${input:max:6} classes.

Run Phase 1 and show me the ordered target list with uncovered methods. After my confirmation, work one class per iteration with a commit each. Stop early when the module target is reached or the list is exhausted. Report the module before/after and per-class table.
