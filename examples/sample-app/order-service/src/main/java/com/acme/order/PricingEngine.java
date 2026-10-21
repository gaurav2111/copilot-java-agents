package com.acme.order;

import com.acme.common.Money;
import org.springframework.stereotype.Component;

/** Deliberately untested: the coverage task should pick this class first. */
@Component
public class PricingEngine {
    static final int BULK_THRESHOLD = 10;
    static final int BULK_DISCOUNT_PERCENT = 5;
    static final int VIP_DISCOUNT_PERCENT = 10;

    public Money total(Order order, boolean vipCustomer) {
        Money total = null;
        int units = 0;
        for (Order.Line line : order.lines()) {
            Money lineTotal = line.unitPrice().times(line.quantity());
            total = total == null ? lineTotal : total.plus(lineTotal);
            units += line.quantity();
        }
        if (total == null) throw new IllegalArgumentException("order has no lines");
        if (units >= BULK_THRESHOLD) total = total.percentOff(BULK_DISCOUNT_PERCENT);
        if (vipCustomer) total = total.percentOff(VIP_DISCOUNT_PERCENT);
        return total;
    }
}
