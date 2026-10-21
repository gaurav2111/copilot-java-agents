---
applyTo: "**/src/test/**"
description: "Conventions for unit and integration tests"
---
# Test conventions

## Layout and naming
- One test class per production class: `FooService` -> `FooServiceTest` (unit, `test` task) and `FooServiceIT` (integration, `integrationTest` task or the module's equivalent source set; Maven: failsafe).
- Same package as the class under test. Method names describe behaviour: `placeOrder_rejectsNegativeQuantity`, or `should...` if the module already uses that.
- Mirror an existing good test in the same module before writing a new one. Copy its setup pattern, not a generic template.

## Unit tests
- JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) + AssertJ. No Hamcrest, no JUnit 4 imports, no PowerMock.
- Arrange / Act / Assert sections. One behaviour per test. Use `@ParameterizedTest` for input tables instead of copy-pasted tests.
- Mock only collaborators that cross a boundary (repositories, clients, clocks, random, time). Do not mock value objects, mappers, or the class under test.
- Assert outcomes, not interactions, unless the interaction is the contract (e.g. an event was published). Avoid `verifyNoMoreInteractions` by default.
- Fixed clocks and seeded randomness. No `Thread.sleep`. No real network, filesystem, or database in a unit test.
- Test data: small builders or factory methods in `src/test/java/.../support`. No real customer data, hostnames, or credentials.

## Integration tests
- Use the narrowest Spring slice that proves the behaviour: `@WebMvcTest`, `@DataJpaTest`, `@JsonTest`, `@RestClientTest` before `@SpringBootTest`.
- External systems via Testcontainers or WireMock, never a shared environment. Containers are static and reused across the class.
- `@SpringBootTest` classes share one context configuration where possible; avoid `@MockBean` / `@MockitoBean` sprawl that forks the context cache.
- Each IT cleans up what it created. Tests must pass in any order and in parallel.

## Hard rules
- Never `@Disabled` a failing test, never loosen an assertion, never catch and ignore to make it green. Report it.
- Never change production code to make a test pass. If a class is untestable, propose the smallest refactor and wait for approval.
- A generated test that only asserts "no exception thrown" or only calls getters is not acceptable; delete it rather than ship it.
