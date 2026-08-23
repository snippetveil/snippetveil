package com.harborlight.billing;

import java.util.Set;

/**
 * The exemptions finance maintains by hand.
 *
 * <p>Kept as an interface rather than a table because the two implementations disagree about where
 * the list lives, and the calculator is not entitled to know which one it has.
 */
public interface SettlementPolicy {

    /** True when this customer is never charged a late fee, whatever the ledger says. */
    boolean isExemptFromLateFees(Customer customer);

    /** A policy that exempts a fixed set of account references and nobody else. */
    static SettlementPolicy exempting(Set<String> accountReferences) {
        return customer -> accountReferences.contains(customer.accountReference());
    }
}
