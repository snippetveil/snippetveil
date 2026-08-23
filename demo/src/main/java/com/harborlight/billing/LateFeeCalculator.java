package com.harborlight.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Works out what to charge a customer for paying late.
 *
 * <p>The rule is the one finance agreed in the March review: a flat administration charge once the
 * grace period lapses, then daily interest on the outstanding gross, capped at a share of the
 * invoice so that a small invoice left unpaid for a year cannot compound into a large one.
 */
public final class LateFeeCalculator {

    private static final BigDecimal ADMINISTRATION_CHARGE = new BigDecimal("35.00");
    private static final BigDecimal DAILY_INTEREST_RATE = new BigDecimal("0.0004");
    private static final BigDecimal CAP_AS_SHARE_OF_INVOICE = new BigDecimal("0.15");
    private static final int GRACE_DAYS = 5;

    private final SettlementPolicy policy;

    public LateFeeCalculator(SettlementPolicy policy) {
        this.policy = policy;
    }

    /**
     * The fee for one overdue invoice, or empty when nothing is owed.
     *
     * <p>Empty rather than zero: "no fee is due" and "a fee of nothing is due" read the same on a
     * statement and mean different things to the collections team.
     */
    public Optional<BigDecimal> feeFor(Invoice invoice, LocalDate today) {
        if (!invoice.isOverdue(today)) {
            return Optional.empty();
        }

        long daysLate = ChronoUnit.DAYS.between(invoice.dueOn(), today);
        if (daysLate <= GRACE_DAYS) {
            return Optional.empty();
        }

        if (policy.isExemptFromLateFees(invoice.customer())) {
            return Optional.empty();
        }

        BigDecimal outstanding = invoice.total();
        BigDecimal interest = outstanding
                .multiply(DAILY_INTEREST_RATE)
                .multiply(BigDecimal.valueOf(daysLate - GRACE_DAYS));

        BigDecimal fee = ADMINISTRATION_CHARGE.add(interest).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cap = outstanding.multiply(CAP_AS_SHARE_OF_INVOICE).setScale(2, RoundingMode.HALF_UP);

        return Optional.of(fee.min(cap));
    }

    /** Every fee owed across a ledger, in the order the dunning run sends them. */
    public BigDecimal totalOwedBy(Customer customer, List<Invoice> ledger, LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        for (Invoice invoice : ledger) {
            if (invoice.customer().id().equals(customer.id())) {
                total = total.add(feeFor(invoice, today).orElse(BigDecimal.ZERO));
            }
        }
        return total;
    }
}
