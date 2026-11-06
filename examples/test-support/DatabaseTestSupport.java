package com.example.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Isolation on a shared test database without truncating anything: every key a test creates carries the run id, and the
 * rows a test registers are deleted in reverse order afterwards. Use @Transactional on the test instead when the flow
 * allows a rollback; use this when the flow commits (a listener, a second transaction, an outbox).
 *
 * Usage:
 *   String orderId = db.uniqueId("order");            // "order-<runId>-1"
 *   db.register("orders", "id", orderId);             // after the flow created the row
 *   @AfterEach void clean() { db.cleanup(); }
 */
public final class DatabaseTestSupport {
    private static final String RUN_ID = Long.toHexString(System.nanoTime());
    private final JdbcTemplate jdbc;
    private final AtomicLong seq = new AtomicLong();
    private final Deque<Object[]> registered = new ArrayDeque<>();

    public DatabaseTestSupport(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String runId() { return RUN_ID; }

    /** a key unique to this run and this JVM: prefix-runId-sequence */
    public String uniqueId(String prefix) { return prefix + "-" + RUN_ID + "-" + seq.incrementAndGet(); }

    /** remember a row to delete at cleanup; register children before parents or rely on the reverse order */
    public void register(String table, String keyColumn, Object keyValue) { registered.push(new Object[]{table, keyColumn, keyValue}); }

    /** deletes the registered rows, most recent first; a row that is already gone is not an error */
    public void cleanup() {
        while (!registered.isEmpty()) {
            Object[] r = registered.pop();
            jdbc.update("delete from " + r[0] + " where " + r[1] + " = ?", r[2]);
        }
    }

    /** rows in a table whose key column holds a value of this run; for the "nothing is left" assertion */
    public int countForThisRun(String table, String keyColumn) {
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where " + keyColumn + " like ?", Integer.class, "%-" + RUN_ID + "-%");
        return n == null ? 0 : n;
    }
}
