package com.acme.order;

import com.acme.common.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Partial coverage on purpose: cancel() and several validation branches are untested. */
class OrderServiceTest {
    private FakeInventory inventory;
    private OrderService service;

    @BeforeEach
    void setUp() {
        inventory = new FakeInventory();
        inventory.stock.put("SKU-1", 5);
        service = new OrderService(inventory, new PricingEngine(), 10);
    }

    @Test
    void place_confirmsOrderAndReservesStock() {
        Order order = service.place(order("SKU-1", 2));
        assertThat(order.status()).isEqualTo(Order.Status.CONFIRMED);
        assertThat(inventory.stock.get("SKU-1")).isEqualTo(3);
        assertThat(service.find(order.id())).isPresent();
    }

    @Test
    void place_rejectsOutOfStock() {
        assertThatThrownBy(() -> service.place(order("SKU-1", 6)))
                .isInstanceOf(OrderService.OutOfStockException.class)
                .hasMessageContaining("SKU-1");
    }

    private static Order order(String sku, int qty) {
        return new Order("o-1", "c-1", List.of(new Order.Line(sku, qty, Money.of("9.99", "EUR"))));
    }

    static final class FakeInventory implements InventoryClient {
        final Map<String, Integer> stock = new HashMap<>();
        @Override public int available(String sku) { return stock.getOrDefault(sku, 0); }
        @Override public void reserve(String sku, int quantity) { stock.merge(sku, -quantity, Integer::sum); }
    }
}
