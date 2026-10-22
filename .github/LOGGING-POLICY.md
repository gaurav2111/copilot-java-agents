# Logging policy (team-owned; the log-engineer agent reads this before adding, changing or removing any log line)

Edit this file to match your platform. Every rule here is applied as written; where the agent disagrees it says so in
`issues.md` and follows the policy anyway.

## 1. Levels mean exactly this
- **ERROR**: a request, job or message failed and someone must look. Always with the exception as the last argument. Never in a loop.
- **WARN**: degraded but handled: a retry succeeded, a fallback was used, a deprecated path ran. Actionable, rate-limited.
- **INFO**: one line per business event or per unit of work: order placed, job finished with counts, external call summary with duration. Readable by someone who does not know the code. The production level.
- **DEBUG**: everything a developer needs to follow one request: branches taken, values decided, payload sizes. Off in production; must be cheap even when off (placeholders, no string building, no `toString()`).
- **TRACE**: per-element and per-iteration detail. Off everywhere except a local run.

## 2. Where logs belong
- **Inbound boundary** (controller, listener, scheduled job): one INFO at completion with the outcome, duration and ids. Not "entering" and "exiting" lines; tracing does that.
- **Outbound boundary** (HTTP client, database, queue producer): one DEBUG per call with target, duration and status; WARN on retry or fallback; ERROR only if the failure propagates.
- **Business events**: INFO, with the identifiers a support engineer would search for.
- **Catch blocks**: either log with the cause and handle, or rethrow; never both, never neither.
- **Loops**: no INFO or above inside a loop. Log once after the loop with a count.
- **Hot paths** (filters, interceptors, repositories, caches, serializers): DEBUG at most, and only when guarded by placeholders.

## 3. Never log
Passwords, tokens, API keys, session ids, cookies, authorization headers, card numbers, bank account numbers, national ids,
email addresses, phone numbers, dates of birth, full addresses, salaries, whole request or response bodies, whole
entities or DTOs, stack traces at INFO. Log identifiers and sizes instead. The lint blocks the obvious cases; the policy
covers the rest.

## 4. How to write a line
- SLF4J with placeholders: `log.info("order {} confirmed for customer {} in {} ms", orderId, customerId, elapsed)`. No `+`, no `String.format`, no `toString()`.
- Stable message text (a constant string the log sink can group on) with variables only in placeholders. The message text is the search key; do not put ids in it.
- Correlation: the request or trace id goes in MDC once at the boundary, never repeated in every message.
- Structured output where the platform ingests JSON: Spring Boot `logging.structured.format.console=ecs|logstash|gelf`, or the project's existing JSON encoder. Field names from the platform's schema, not invented.
- One logger per class, private static final, named for the enclosing class (or the project's Lombok annotation).

## 5. Volume and cost rules
- Production root level INFO; framework packages (`org.hibernate`, `org.springframework`, `org.apache`, `io.netty`, `org.apache.kafka`) at WARN unless a ticket says otherwise.
- No DEBUG or TRACE package level committed in a production profile.
- Repeated identical messages are rate-limited or deduplicated by the appender, not by code.
- A reduction task reports volume before and after as bytes per day and cost per day; the price per GB comes from the platform team: <fill in: sink and price per GB>.
- Removing a line is allowed only when it is noise by section 2, or the measured volume shows it and no runbook references it.

## 6. What stays as it is
<!-- Lines or packages the agent must not touch, for example audit logs required by compliance. -->
- Audit and security event logs under `**/audit/**` are not changed.
