package com.acme.order;

import com.acme.common.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {
    public record LineRequest(String sku, int quantity, String unitPrice) {}
    public record OrderRequest(String customerId, List<LineRequest> lines) {}

    private final OrderService service;
    private final PricingEngine pricing;

    public OrderController(OrderService service, PricingEngine pricing) { this.service = service; this.pricing = pricing; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> place(@RequestBody OrderRequest req) {
        List<Order.Line> lines = req.lines().stream().map(l -> new Order.Line(l.sku(), l.quantity(), Money.of(l.unitPrice(), "EUR"))).toList();
        Order order = service.place(new Order(UUID.randomUUID().toString(), req.customerId(), lines));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", order.id(), "status", order.status(), "total", pricing.total(order, false).toString()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable String id) {
        return service.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    public Order cancel(@PathVariable String id) { return service.cancel(id); }

    @ExceptionHandler(OrderService.OutOfStockException.class)
    public ResponseEntity<Map<String, String>> outOfStock(OrderService.OutOfStockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
