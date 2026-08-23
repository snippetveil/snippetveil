package com.harborlight.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The nightly job that decides who gets chased.
 *
 * <p>It reads the ledger, applies the late fee rules and produces one reminder per customer rather
 * than one per invoice — a customer with nine overdue invoices gets one letter listing nine, which
 * is what the collections team asked for after the incident in the spring.
 */
public final class DunningRun {

    private final LateFeeCalculator calculator;
    private final InvoiceRepository repository;

    public DunningRun(LateFeeCalculator calculator, InvoiceRepository repository) {
        this.calculator = calculator;
        this.repository = repository;
    }

    public List<Reminder> remindersFor(LocalDate today) {
        Map<String, Reminder> byAccount = new TreeMap<>();

        for (Invoice invoice : repository.issued()) {
            if (!invoice.isOverdue(today)) {
                continue;
            }

            BigDecimal fee = calculator.feeFor(invoice, today).orElse(BigDecimal.ZERO);
            String accountReference = invoice.customer().accountReference();

            Reminder reminder = byAccount.computeIfAbsent(
                    accountReference,
                    reference -> new Reminder(invoice.customer(), new ArrayList<>(), BigDecimal.ZERO));

            reminder.add(invoice, fee);
        }

        return new ArrayList<>(byAccount.values());
    }

    /** One letter, covering every overdue invoice a customer has. */
    public static final class Reminder {

        private final Customer customer;
        private final List<Invoice> overdue;
        private BigDecimal fees;

        Reminder(Customer customer, List<Invoice> overdue, BigDecimal fees) {
            this.customer = customer;
            this.overdue = overdue;
            this.fees = fees;
        }

        void add(Invoice invoice, BigDecimal fee) {
            overdue.add(invoice);
            fees = fees.add(fee);
        }

        public Customer customer() {
            return customer;
        }

        public List<Invoice> overdue() {
            return overdue;
        }

        public BigDecimal fees() {
            return fees;
        }

        public String subjectLine() {
            return "Overdue invoices for " + customer.tradingName();
        }
    }
}
