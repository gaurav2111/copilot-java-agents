---
applyTo: "**/src/main/java/**/*.java"
description: "Conventions for production Java code in this repository"
---
# Production Java conventions

- Match the style of the surrounding file (indentation, import order, `final` usage, Lombok or not). Do not reformat code you did not change.
- Java level: write code valid for the lowest Java version the module compiles with (see pom `maven.compiler.release`). Do not introduce newer syntax during an upgrade PR; modernisation is a separate task.
- Null handling: follow the module's existing convention (`Optional` return types, `@Nullable` annotations, or Objects.requireNonNull). Never introduce a second convention.
- Logging: SLF4J via the existing logger field pattern. Parameterised messages, no string concatenation, no logging of secrets or PII.
- Exceptions: reuse the module's exception hierarchy. Do not swallow exceptions; do not wrap checked exceptions in bare `RuntimeException`.
- Spring: constructor injection only. No field injection. No new `@Component` scanning tricks; register beans the way the module already does.
- Dependencies: no new third-party dependency without stating it in the plan. Prefer what the Spring Boot BOM already manages.
- Deprecations: replace deprecated API usages only when the task is an upgrade or the deprecated method is removed in the target version. Note every replacement in the summary.
- Concurrency, transactions, and security annotations (`@Transactional`, `@PreAuthorize`, `@Async`) must not be added, removed, or moved without explicit mention in the plan.
- Public API (classes, methods, DTO fields exposed over HTTP or messaging) keeps its signature and serialised shape unless the task says otherwise.
