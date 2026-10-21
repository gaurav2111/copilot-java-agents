---
applyTo: "**/*.md,**/docs/**,**/*.puml,**/*.mmd"
description: "Conventions for documentation files and diagrams"
---
# Documentation conventions

- Open with two sentences: who the document is for and what question it answers. Then a table of contents only if the document exceeds ~80 lines.
- Diagrams are Mermaid in fenced ```mermaid blocks, one question per diagram, at most ~25 nodes or ~20 messages. Quote labels with parentheses or colons. PlantUML only where the repository already uses it.
- Name code precisely: identifiers in backticks, file path on first mention, HTTP verb plus path for endpoints, fully qualified topic/queue/table names for infrastructure.
- Behaviour statements are specific and present tense, including the error path and the status code or exception.
- Never include secret values, credentials, tokens, internal hostnames, account ids, or customer-like data. Property names are fine; values are not. Replace examples with obviously fake ones (`example.internal`, `ORDER-000001`).
- Every architectural or behavioural document ends with `## Sources` (files and commit), `## Not covered`, and `## Unverified`.
- No marketing language, no "robust", "seamless", "leverages". No speculation about intent; write `TODO(owner): why ...?` instead.
- Keep existing document structure and index files (`docs/README.md`, ADR index) in sync when adding a document. Dates in ISO format.
- Do not duplicate what the code generates (OpenAPI, Javadoc site); link to it and document what it cannot express.
