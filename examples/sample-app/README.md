# sample-app

Throwaway Gradle multi-project used to test the Copilot agent pack
(https://github.com/gaurav2111/copilot-java-agents). Every flaw is deliberate:

| Where | Flaw | Agent that targets it |
|---|---|---|
| `gradle/libs.versions.toml` | Spring Boot 2.7.18 (snakeyaml 1.30, jackson-databind 2.13.5), commons-text 1.9, guava 30.1-jre; see `findings.csv` | security-fixer (`/fix-cves findings.csv`) |
| `order-service/.../OrderService.java` | `javax.annotation.PostConstruct` | upgrader / migrator |
| `legacy-reports` | JUnit 4 + Hamcrest tests | migrator (`/migrate legacy-reports junit4 -> junit5`) |
| `order-service/.../PricingEngine.java`, `OrderController.java` | no tests | test-engineer (`/raise-coverage order-service 75 4`) |
| `OrderService`, `InMemoryInventoryClient`, `ReportFormatter`, `application.yml`, `log-export.csv` | concatenated and entry/exit logs, INFO in a loop with an email, `System.out`, an error without its cause, framework packages at DEBUG, and a fake log-sink export | log-engineer (`/audit-logs . log-export.csv 0.50`) |
| branch `chore/java21` | Java 17 -> 21 done by the upgrader simulation; see `.agent-work/` | upgrader |

Branches: `main` = baseline on Java 17, `chore/java21` = after the upgrader run, with the task's
`.agent-work/` folder (facts, decisions, plan, issues, report) committed for reference.

Build: `./gradlew build` (first run online). Scripts: see `../copilot-java-agents/TESTING.md` Stage 2.
