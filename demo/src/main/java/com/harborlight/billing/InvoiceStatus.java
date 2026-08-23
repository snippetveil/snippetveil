package com.harborlight.billing;

/** Where an invoice is in its life. */
public enum InvoiceStatus {
    DRAFT,
    ISSUED,
    SETTLED,
    WRITTEN_OFF
}
