---
description: "Upgrade a module to a target Java version (recipe first, residue second)"
agent: upgrader
argument-hint: "module targetJava - e.g. 'order-service 21'"
---
Upgrade module `${input:module}` from its current Java version to Java ${input:target:21}.

Follow Phase 0 to 4. Stop after Phase 1 and after the Phase 2 dry run to show me the summary before applying. No syntax modernisation. Report in the standard format.
