---
description: "Write unit tests for one or more named production classes"
agent: test-engineer
argument-hint: "module ClassA,ClassB"
---
Write unit tests in module `${input:module}` for: ${input:classes}.

Phase 0 first (learn the dialect, name the exemplar you mirrored). For each class: list the behaviours as test names, wait for my OK on the list, then write, run only that test class, and report before/after coverage for that class. Do not touch production code.
