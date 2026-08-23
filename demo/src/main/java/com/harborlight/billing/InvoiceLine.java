package com.harborlight.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** One line of an invoice: what was sold, how much of it, and at what price. */
public final class InvoiceLine {

    private final String id;
    private final String description;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal taxRate;

    public InvoiceLine(String id, String description, int quantity, BigDecimal unitPrice, BigDecimal taxRate) {
        this.id = id;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.taxRate = taxRate;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public int quantity() {
        return quantity;
    }

    /** Net of tax, rounded the way the tax authority expects rather than the way BigDecimal defaults. */
    public BigDecimal net() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal tax() {
        return net().multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal gross() {
        return net().add(tax());
    }
}
