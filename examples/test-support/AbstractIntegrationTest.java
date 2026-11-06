package com.example.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * One base class per module. The test profile reads the connection values from the environment
 * (TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD, PUBSUB_EMULATOR_HOST); nothing here holds a host, a user or a password.
 * Every IT in the module extends this class so the Spring context is built once per configuration.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
    protected static final String EMULATOR_HOST = System.getenv().getOrDefault("PUBSUB_EMULATOR_HOST", "localhost:8085");
    protected static final PubSubTestSupport pubsub = new PubSubTestSupport(EMULATOR_HOST, "test-project");

    @Autowired protected JdbcTemplate jdbcTemplate;
    protected DatabaseTestSupport db;

    @DynamicPropertySource
    static void emulator(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gcp.pubsub.emulator-host", () -> EMULATOR_HOST);
        registry.add("spring.cloud.gcp.project-id", () -> "test-project");
    }

    protected DatabaseTestSupport db() {
        if (db == null) db = new DatabaseTestSupport(jdbcTemplate);
        return db;
    }

    @AfterEach
    void cleanRegisteredRows() { if (db != null) db.cleanup(); }

    @AfterAll
    static void closePubSub() { pubsub.close(); }
}
