package com.acme.order;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryInventoryClient implements InventoryClient {
    private final Map<String, Integer> stock = new ConcurrentHashMap<>(Map.of("SKU-1", 10, "SKU-2", 0));
    @Override public int available(String sku) { return stock.getOrDefault(sku, 0); }
    @Override public void reserve(String sku, int quantity) { stock.merge(sku, -quantity, Integer::sum); }
}
