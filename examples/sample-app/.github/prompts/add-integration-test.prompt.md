---
description: "Write an integration test for a business flow using the narrowest Spring slice and Testcontainers/WireMock"
agent: test-engineer

argument-hint: "module 'flow description' - e.g. 'order-service: POST /orders with an out-of-stock item returns 409'"
---
Write an integration test in `${input:module}` for this flow: ${input:flow}

Choose and justify the slice (WebMvcTest / DataJpaTest / SpringBootTest). Reuse the module's existing container and profile setup. Show me the test plan (setup, steps, assertions, cleanup) before writing. Run it alone, then with the module's other ITs once.
