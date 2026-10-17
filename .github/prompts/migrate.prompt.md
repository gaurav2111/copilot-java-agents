---
description: "Run a framework/library migration (JUnit4->5, javax->jakarta, Log4j->SLF4J, ...) phased and recipe-first"
agent: migrator
argument-hint: "module from -> to - e.g. 'legacy-core junit4 -> junit5'"
---
Migrate `${input:module}` from ${input:from} to ${input:to}.

Phase 0 inventory and coverage check first; tell me whether characterisation tests are needed before anything else. Dry-run the recipe and show me the file list before applying. Manual residue by seam, one commit per seam. Zero remaining references at the end, old dependency removed.
