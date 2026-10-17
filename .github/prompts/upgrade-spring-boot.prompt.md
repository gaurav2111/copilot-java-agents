---
description: "Upgrade Spring Boot (and the Spring Framework it brings) for a module or the whole reactor"
agent: upgrader
argument-hint: "module targetVersion - e.g. 'all 3.5' or 'billing 4.0'"
---
Upgrade Spring Boot in `${input:module:all}` to ${input:target}.

Rules for this run:
- If the hop crosses a major version, do the highest 2.x/3.x minor first as its own PR, then the major.
- Use the `UpgradeSpringBoot_<target>` composite; dry run first and show me the file list.
- Run the properties migrator once on the test classpath and show me the renames it reports before applying them.
- Spring Security and Hibernate behaviour changes go into the Risks section even if they compile.
Report in the standard format.
