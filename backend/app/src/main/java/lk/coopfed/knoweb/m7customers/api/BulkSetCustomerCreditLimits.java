package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Bulk command to set customer account credit limits from a sheet or rows (CAT-14).
 */
public record BulkSetCustomerCreditLimits(String sheet, List<CustomerLimitRow> limits, String defaultReason) {

    public BulkSetCustomerCreditLimits {
        limits = limits == null ? List.of() : List.copyOf(limits);
    }

    public record CustomerLimitRow(
            String accountNo, String phone, String nic, UUID accountId, BigDecimal creditLimit, String reason) {}
}
