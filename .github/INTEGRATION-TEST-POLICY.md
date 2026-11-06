# Integration test policy (team-owned; the test-engineer reads this before any integration test)

Everything runs on the developer's machine, in the JVM, against services the developer already has: no containers, no
cloud projects, no credentials in the repository. The agent never chooses a substitute on its own: for every external
system a flow touches it asks one numbered question at G1, with the options from this file, and writes the answer in
`decisions.md` and in one comment line at the top of the test class.

## 1. Per system: the options the agent offers (first option is the team default)

| System | Options offered at G1 | Facts the agent needs from `scripts/it-env.sh` |
|---|---|---|
| Relational database | 1. The test database the developer points at through the properties below. 2. Stop. (No H2, no embedded database: the tests prove the real dialect.) | properties set, host and port reachable |
| Pub/Sub | 1. The local emulator (`gcloud` Pub/Sub emulator started by the developer), with the `PubSubTestSupport` helper for topics, subscriptions and purge. 2. A fake at the port the application owns (publisher or subscriber interface) when the flow does not need real delivery. 3. Stop. | emulator host reachable, `gcloud` present |
| Google Cloud Storage | 1. In-memory `Storage` from `google-cloud-nio` (`LocalStorageHelper`): upload, download, list; not signed URLs or IAM. 2. A fake at the application's own port. 3. Stop. Never the real bucket. | `google-cloud-nio` on the test classpath or proposable |
| HTTP downstream | 1. WireMock in the JVM, or `@RestClientTest` with `MockRestServiceServer` for a single client. 2. A fake at the application's client interface. | WireMock or `spring-boot-starter-test` on the test classpath |
| Kafka | 1. `spring-kafka-test` embedded broker. 2. A fake at the application's producer or consumer port. | `spring-kafka-test` present or proposable |
| Redis, caches | 1. `ConcurrentMapCacheManager` in the test profile. 2. A fake at the port. | cache configuration found |
| Firestore, Spanner, BigQuery, other GCP services | 1. A fake at the application's own port. 2. `@MockitoBean` on the client bean, as the last resort. 3. Stop. | client beans found |
| Security | `spring-security-test`: `@WithMockUser`, `jwt()` or `oauth2Login()` post-processors. Never disabled. | `spring-security-test` present |

## 2. Database connection and isolation

- Connection comes from the developer, never from the agent: environment variables `TEST_DB_URL`, `TEST_DB_USER`,
  `TEST_DB_PASSWORD` read by the test profile (`spring.datasource.url=${TEST_DB_URL}` in `application-test.yml`), or
  values the developer writes into a local, git-ignored `application-test-local.yml`. The agent reports which of these
  is set and never writes a URL, a user or a password into any file.
- The database may be shared. Isolation rules, confirmed at G1: every key the test creates carries the run id from
  `DatabaseTestSupport.runId()`; a test rolls back with `@Transactional` when the flow allows it; otherwise it deletes
  only the rows it registered, in `@AfterEach`, through `DatabaseTestSupport`. No `TRUNCATE`, no `DROP`, no schema change,
  no `spring.jpa.hibernate.ddl-auto` other than `none` or `validate`.
- Migrations: the agent asks whether Flyway or Liquibase may run against the test database or must be disabled in the
  test profile (`spring.flyway.enabled=false`). Default: disabled; the database is migrated by the team.

## 3. Pub/Sub emulator

- Started by the developer: `gcloud beta emulators pubsub start --host-port=localhost:8085`. The test profile sets
  `spring.cloud.gcp.pubsub.emulator-host=${PUBSUB_EMULATOR_HOST:localhost:8085}` and no credentials; the agent checks the
  port answers and asks the developer to start the emulator when it does not.
- `PubSubTestSupport` (template in `examples/test-support/`): creates the topics and subscriptions a test needs if they
  are missing, purges a subscription before each test (seek to now, pull-and-ack fallback), publishes, pulls with a
  timeout for assertions, and deletes what the class created. Names carry the run id so two developers on one emulator
  do not collide.

## 4. Test-side changes the agent may make without a question

`@TestConfiguration` beans, `@MockitoBean` on a gateway the G1 answer allowed, `@DynamicPropertySource`,
`spring.autoconfigure.exclude` for an auto-configuration of a system the answer excluded, `@Import` of a subset, the
shared base class, the support helpers, entries in the test profile that are not connection values.

## 5. Never without a decision in `decisions.md`

Editing a production class (a port interface, a seam, a property toggle: propose and wait), an `@Primary` bean outside
test sources, disabling security or CSRF, `spring.main.allow-bean-definition-overriding`, a new test dependency, a real
project id, bucket, topic or credential.

## 6. One base class per module

`AbstractIntegrationTest` (template in `examples/test-support/`): `@SpringBootTest`, `@ActiveProfiles("test")`, the run
id, the helpers as beans, the emulator host from the environment. Written once after a go, reused by every IT in the
module, so the Spring context is built once per configuration. Each IT is `<Flow>IT`, in the module's integration
source set or `integrationTest` task (Maven: failsafe), and passes in any order.

## 7. Flow checklist (what "complete" means for one flow)

`happy` the documented outcome asserted on the response and on what is left in the database or the subscription;
`validation` each rejected input with status and message; `downstream` the dependency fails, times out or returns
empty; `auth` the forbidden and the anonymous caller; `replay` the same request twice; `cleanup` nothing of the test
remains afterwards. A key that does not apply is listed with its reason, as for unit tests.

## 8. Runs only in CI

Nothing by default. A test that needs something a developer machine cannot have is marked `@Tag("ci-only")` with the
reason, and the module's test task excludes the tag locally; the agent proposes this instead of a weaker test.
