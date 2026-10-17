---
description: "Document an application, module, flow, endpoint or class in the format you want (sequence/flowchart/ER/C4 diagram, README, API reference, runbook, Javadoc, ADR, plain-language summary)"
agent: docs-writer
argument-hint: "subject | format | audience - e.g. 'order placement flow | sequence | new backend dev' or ':billing | readme' or 'InvoiceCalculator | javadoc'"
---
Document: ${input:subject}
Format: ${input:format:ask me, with your recommendation}
Audience: ${input:audience:a developer new to this module}

Run Phase 0 discovery first and show me the candidate file list with the read budget you intend to use. After my OK, trace and write. Keep every statement traceable to a file; mark anything inferred as unverified. Finish with the Sources / Not covered / Unverified sections and the five-line report.
