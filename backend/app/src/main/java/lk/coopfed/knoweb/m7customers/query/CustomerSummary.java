package lk.coopfed.knoweb.m7customers.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the customer register: who, how to reach them, and their account at the caller's
 * society when there is one (null account fields when not). Never a NIC.
 */
public record CustomerSummary(
        UUID customerId,
        String displayName,
        String displayNameSi,
        String displayNameTa,
        String language,
        String phone,
        String status,
        UUID accountId,
        String accountNo,
        BigDecimal creditLimit,
        BigDecimal balance) {}
