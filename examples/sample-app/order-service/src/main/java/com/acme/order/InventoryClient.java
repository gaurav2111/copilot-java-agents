package com.acme.order;

/** Boundary to the inventory system. Real implementation would call acme.order.inventory-url. */
public interface InventoryClient {
    int available(String sku);
    void reserve(String sku, int quantity);
}
