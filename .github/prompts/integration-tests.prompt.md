---
description: "Integration tests for an endpoint, flow or whole module without containers: real test database by your connection, local Pub/Sub emulator, in-memory or port fakes elsewhere; every substitute is a question you answer; one commit point per flow through the flow checklist"
agent: test-engineer
argument-hint: "module | endpoint, flow in words, or 'all' | optional: systems the flows touch (db, pubsub, gcs, http, kafka)"
---
Write integration tests in module `${input:module}` for `${input:target}`. Systems touched, if known: ${input:systems:detect from the code}.

The rules are in `.github/INTEGRATION-TEST-POLICY.md`: everything runs in the JVM on this machine, nothing is pulled or started by you, connection values come from me, and every substitute is a numbered question I answer.

Do this, in order, and stop where it says:
1. Facts: `scripts/it-env.sh ${input:module}`. Record its lines in `facts.md`. Read one existing integration or slice test in the module if there is one and name it.
2. Flows: for an endpoint or 'all', list the flows from the controller mappings and listeners (one line each: entry point, condition, observable outcome); for a flow in words, restate it in that form. For each flow, the systems it touches.
3. G1: one numbered question per system from policy section 1, in the order the flows need them; for the database also the isolation question (rollback, or register-and-delete) and the migration question (Flyway or Liquibase disabled in the test profile, or allowed). Tell me which connection values are missing (`TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`, `PUBSUB_EMULATOR_HOST`) and which hosts do not answer; I set them, you never write them. WAIT.
4. Plan: the base class `AbstractIntegrationTest` for the module (from `examples/test-support/`, adapted to the module's package and dialect, only the helpers the answers need), the test profile entries that are not connection values, any test dependency to add (a question, not an edit), then one `<Flow>IT` per flow with the checklist keys it will cover (`happy validation downstream auth replay cleanup`) and the keys that do not apply with the reason. Each flow is its own COMMIT POINT. WAIT.
5. On go: write the base class and the helpers first, run one existing or trivial test through it to prove the context starts (`scripts/test-summary.sh ${input:module} <ITClass>`), COMMIT POINT. Then per flow: write `<Flow>IT`, one test method per checklist key, assertions on the response and on what is left in the database or the subscription, `scripts/test-summary.sh ${input:module} <Flow>IT`, then the module's whole IT set once, COMMIT POINT. A flow that exposes a production bug keeps its test `@Disabled("BUG-...: ...")` and goes into the report.
6. Report: per flow the checklist keys covered and not applicable, tests added, the substitutes used with their reason, connection values the next developer must set, bugs found, refactors proposed, what runs only in CI with its tag.

Never: a URL, user, password, project id, bucket or topic name of a real environment in any file; a truncate or schema change on the test database; a production code change; a dependency added without my answer; a test that only proves the context starts.
