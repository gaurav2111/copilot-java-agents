package com.acme.order;

import com.acme.common.Money;
import java.util.List;
import java.util.Objects;

public final class Order {
    public enum Status { NEW, CONFIRMED, CANCELLED }
    public record Line(String sku, int quantity, Money unitPrice) {}

    private final String id;
    private final String customerId;
    private final List<Line> lines;
    private Status status = Status.NEW;

    public Order(String id, String customerId, List<Line> lines) {
        this.id = Objects.requireNonNull(id); this.customerId = Objects.requireNonNull(customerId); this.lines = List.copyOf(lines);
    }
    public String id() { return id; }
    public String customerId() { return customerId; }
    public List<Line> lines() { return lines; }
    public Status status() { return status; }
    void status(Status s) { this.status = s; }
}
