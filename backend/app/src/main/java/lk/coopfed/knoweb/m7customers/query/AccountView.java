package lk.coopfed.knoweb.m7customers.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A credit account (27A section 5, GET /v1/accounts/{id}): the limit, the balance (the sum of
 * the postings), what is still available, and the ageing of the open charges.
 *
 * @param available    the limit less the balance, never below zero
 * @param unallocated  what payments hold beyond the charges they settled (a credit balance)
 * @param oldestUnpaid the business date of the oldest charge not fully settled; null when none
 */
public record AccountView(
        UUID accountId,
        String accountNo,
        UUID customerId,
        BigDecimal creditLimit,
        BigDecimal balance,
        BigDecimal available,
        BigDecimal offlineCap,
        int termsDays,
        boolean hardBlock,
        String status,
        Instant openedAt,
        LocalDate oldestUnpaid,
        BigDecimal unallocated,
        Ageing ageing) {

    /** The open amount of the charges by age in days on the business date (27A section 7). */
    public record Ageing(BigDecimal days0To30, BigDecimal days31To60, BigDecimal days61To90, BigDecimal over90) {}
}
