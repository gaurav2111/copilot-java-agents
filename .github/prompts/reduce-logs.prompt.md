---
description: "Reduce log volume and cost in a module or the repository, driven by measured sink volume where available, under the logging policy"
agent: log-engineer
argument-hint: "module or '.'  target (e.g. '30 percent' or '$40/day')  [export.csv]"
---
Reduce logging in `${input:scope:.}` with target ${input:target:30 percent of volume}${input:export:, using the sink export at ${input:export}}.

Phase 0 facts, then G1: confirm the sink and price per GB, and ask for the list of message texts that alerts or
runbooks depend on. After my answers: the LoggingHygiene recipe pass as its own COMMIT POINT, then reduction by theme,
config first, one COMMIT POINT per theme, with audit counts after each. Finish with bytes per day and $ per day before
and after, measured where the export allows it and marked as projected otherwise.
