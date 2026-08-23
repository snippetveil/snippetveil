package com.harborlight.billing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The ledger, in memory.
 *
 * <p>The real one is behind the persistence layer; this is the shape the rest of the package codes
 * against, and it is deliberately the whole interface rather than a subset of a larger one.
 */
public final class InvoiceRepository {

    private final Map<UUID, Invoice> byId = new LinkedHashMap<>();

    public void save(Invoice invoice) {
        byId.put(invoice.id(), invoice);
    }

    public Optional<Invoice> find(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Every invoice still awaiting settlement, oldest first. */
    public List<Invoice> issued() {
        List<Invoice> issued = new ArrayList<>();
        for (Invoice invoice : byId.values()) {
            if (invoice.status() == InvoiceStatus.ISSUED) {
                issued.add(invoice);
            }
        }
        issued.sort((left, right) -> left.issuedOn().compareTo(right.issuedOn()));
        return issued;
    }

    public int size() {
        return byId.size();
    }
}
