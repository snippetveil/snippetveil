package com.harborlight.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * An issued invoice.
 *
 * <p>Lines are immutable once the invoice is issued — a correction is a credit note, never an edit,
 * because the finance export reconciles against what was sent rather than against what we now think
 * it should have said.
 */
public final class Invoice {

    private final UUID id;
    private final Customer customer;
    private final LocalDate issuedOn;
    private final List<InvoiceLine> lines;
    private InvoiceStatus status;

    public Invoice(UUID id, Customer customer, LocalDate issuedOn, List<InvoiceLine> lines) {
        this.id = id;
        this.customer = customer;
        this.issuedOn = issuedOn;
        this.lines = List.copyOf(lines);
        this.status = InvoiceStatus.ISSUED;
    }

    public UUID id() {
        return id;
    }

    public Customer customer() {
        return customer;
    }

    public LocalDate issuedOn() {
        return issuedOn;
    }

    public List<InvoiceLine> lines() {
        return Collections.unmodifiableList(lines);
    }

    public InvoiceStatus status() {
        return status;
    }

    public LocalDate dueOn() {
        return issuedOn.plusDays(customer.terms().days());
    }

    public BigDecimal total() {
        BigDecimal total = BigDecimal.ZERO;
        for (InvoiceLine line : lines) {
            total = total.add(line.gross());
        }
        return total;
    }

    public boolean isOverdue(LocalDate today) {
        return status == InvoiceStatus.ISSUED && today.isAfter(dueOn());
    }

    public void markSettled() {
        if (status != InvoiceStatus.ISSUED) {
            throw new IllegalStateException("Only an issued invoice can be settled: " + status);
        }
        this.status = InvoiceStatus.SETTLED;
    }

    /** The lines the reconciliation report groups by, in the order the export expects. */
    public List<String> descriptions() {
        List<String> descriptions = new ArrayList<>();
        for (InvoiceLine line : lines) {
            descriptions.add(line.description());
        }
        return descriptions;
    }
}
