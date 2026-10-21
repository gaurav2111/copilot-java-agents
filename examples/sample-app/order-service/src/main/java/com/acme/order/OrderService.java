package com.acme.order;

import com.acme.common.TextUtils;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;   // javax.* on purpose: Jakarta migration residue for the upgrader/migrator
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final InventoryClient inventory;
    private final PricingEngine pricing;
    private final int maxQuantity;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public OrderService(InventoryClient inventory, PricingEngine pricing, @Value("${acme.order.max-quantity:50}") int maxQuantity) {
        this.inventory = inventory; this.pricing = pricing; this.maxQuantity = maxQuantity;
    }

    @PostConstruct
    void init() { log.info("OrderService ready, maxQuantity={}", maxQuantity); }

    public Order place(Order order) {
        if (order.lines().isEmpty()) throw new IllegalArgumentException("order must have at least one line");
        for (Order.Line line : order.lines()) {
            if (Strings.isNullOrEmpty(line.sku())) throw new IllegalArgumentException("sku missing");
            if (line.quantity() <= 0) throw new IllegalArgumentException("quantity must be positive: " + line.sku());
            if (line.quantity() > maxQuantity) throw new IllegalArgumentException("quantity exceeds " + maxQuantity + ": " + line.sku());
            if (inventory.available(line.sku()) < line.quantity()) throw new OutOfStockException(line.sku());
        }
        order.lines().forEach(l -> inventory.reserve(l.sku(), l.quantity()));
        order.status(Order.Status.CONFIRMED);
        orders.put(order.id(), order);
        log.info(TextUtils.fill("order ${id} confirmed for ${customer}", Map.of("id", order.id(), "customer", order.customerId())));
        return order;
    }

    public Optional<Order> find(String id) { return Optional.ofNullable(orders.get(id)); }

    public Order cancel(String id) {
        Order order = orders.get(id);
        if (order == null) throw new IllegalArgumentException("unknown order " + id);
        if (order.status() == Order.Status.CANCELLED) return order;
        order.lines().forEach(l -> inventory.reserve(l.sku(), -l.quantity()));
        order.status(Order.Status.CANCELLED);
        return order;
    }

    public static class OutOfStockException extends RuntimeException {
        public OutOfStockException(String sku) { super("out of stock: " + sku); }
    }
}
