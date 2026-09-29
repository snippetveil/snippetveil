package com.harborlight.billing;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What each customer still owes, read straight from the ledger tables for the month-end report.
 */
public final class ExposureReport {

    private final Connection ledger;

    public ExposureReport(Connection ledger) {
        this.ledger = ledger;
    }

    public Map<String, BigDecimal> outstandingByCustomer() throws SQLException {
        Map<String, BigDecimal> exposure = new LinkedHashMap<>();
        // language=SQL
        String sql = "SELECT customer_ref, sum(balance_due) FROM billing.open_invoices "
                + "GROUP BY customer_ref ORDER BY 2 DESC";
        try (Statement statement = ledger.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                exposure.put(rows.getString(1), rows.getBigDecimal(2));
            }
        }
        return exposure;
    }
}
