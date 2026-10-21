package com.acme.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class Money {
    private final BigDecimal amount;
    private final String currency;

    public Money(BigDecimal amount, String currency) {
        this.amount = Objects.requireNonNull(amount).setScale(2, RoundingMode.HALF_UP);
        this.currency = Objects.requireNonNull(currency);
    }
    public static Money of(String amount, String currency) { return new Money(new BigDecimal(amount), currency); }
    public Money plus(Money other) { requireSameCurrency(other); return new Money(amount.add(other.amount), currency); }
    public Money times(int factor) { return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency); }
    public Money percentOff(int percent) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent out of range: " + percent);
        return new Money(amount.multiply(BigDecimal.valueOf(100 - percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP), currency);
    }
    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
    }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    @Override public boolean equals(Object o) { return o instanceof Money m && amount.equals(m.amount) && currency.equals(m.currency); }
    @Override public int hashCode() { return Objects.hash(amount, currency); }
    @Override public String toString() { return amount + " " + currency; }
}
