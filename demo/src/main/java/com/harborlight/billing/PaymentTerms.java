package com.harborlight.billing;

/** How long a customer has to settle, counted from the invoice date. */
public enum PaymentTerms {

    NET_7(7),
    NET_14(14),
    NET_30(30),

    /** Agreed by hand, for the three accounts the sales team negotiated separately. */
    NET_60(60);

    private final int days;

    PaymentTerms(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }
}
