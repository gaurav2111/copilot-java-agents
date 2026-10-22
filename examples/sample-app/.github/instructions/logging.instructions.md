---
applyTo: "**/logback*.xml,**/log4j2*.xml,**/log4j2*.properties,**/application*.yml,**/application*.yaml,**/application*.properties"
description: "Rules for logging configuration files"
---
# Logging configuration rules

- Production profiles: root level INFO; framework packages (`org.hibernate`, `org.springframework`, `org.apache`, `io.netty`, `org.apache.kafka`) at WARN; no DEBUG or TRACE package level in a profile that can reach production.
- A DEBUG level for one package is allowed only in a local or test profile, with a comment saying why.
- Structured output is set in one place (Spring Boot `logging.structured.format.*` or the single JSON encoder the project uses), with the field names of the log platform; do not invent a second format.
- Appenders: asynchronous for file and network sinks; a duplicate-message filter where the policy asks for it; never both a console and a file appender in a container profile.
- Never commit a change to a logging level without the reason and ticket in a comment next to it.
- `.github/LOGGING-POLICY.md` is the source of truth; when a config change conflicts with it, follow the policy and note the conflict in `issues.md`.
