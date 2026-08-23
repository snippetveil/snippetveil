package com.harborlight.billing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A billing customer, as the ledger knows them.
 *
 * <p>The account reference is the number the finance team quotes on the phone; the id is ours and
 * appears nowhere outside this system.
 */
public final class Customer {

    private final UUID id;
    private final String accountReference;
    private final String tradingName;
    private final Instant onboardedAt;
    private final PaymentTerms terms;

    public Customer(UUID id, String accountReference, String tradingName, Instant onboardedAt, PaymentTerms terms) {
        this.id = Objects.requireNonNull(id, "id");
        this.accountReference = Objects.requireNonNull(accountReference, "accountReference");
        this.tradingName = tradingName;
        this.onboardedAt = onboardedAt;
        this.terms = terms == null ? PaymentTerms.NET_30 : terms;
    }

    public UUID id() {
        return id;
    }

    public String accountReference() {
        return accountReference;
    }

    public String tradingName() {
        return tradingName;
    }

    public Instant onboardedAt() {
        return onboardedAt;
    }

    public PaymentTerms terms() {
        return terms;
    }

    /** True once the customer has been with us long enough to earn the settlement discount. */
    public boolean qualifiesForSettlementDiscount(Instant now) {
        return onboardedAt != null && onboardedAt.plusSeconds(SECONDS_IN_A_YEAR).isBefore(now);
    }

    private static final long SECONDS_IN_A_YEAR = 31_536_000L;

    @Override
    public String toString() {
        return "Customer[" + accountReference + "]";
    }
}
