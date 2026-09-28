package lk.coopfed.knoweb.m7customers.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The credit book of the caller's society (27A section 7, AccountQueries). Balances are OWN only. */
public interface AccountQueries {

    Optional<AccountView> account(UUID accountId, ScopeContext scope);

    /**
     * The statement of an account for the business dates {@code from} to {@code to}, both
     * included: the balance brought forward, every posting with its running balance, and the
     * balance carried forward.
     */
    Optional<Statement> statement(UUID accountId, LocalDate from, LocalDate to, ScopeContext scope);

    /** A customer payment receipt (CPR): its number, account and what it settled. */
    Optional<Payment> payment(UUID documentId, ScopeContext scope);

    record Payment(UUID documentId, String docNumber, UUID accountId, BigDecimal amount, BigDecimal allocated) {}

    record Statement(
            UUID accountId,
            LocalDate from,
            LocalDate to,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            List<Line> lines) {}

    /**
     * One posting. For a CHARGE {@code settled} is what payments settled of it; for a PAYMENT it
     * is what the payment settled of the charges.
     */
    record Line(
            UUID postingId,
            LocalDate businessDate,
            String kind,
            BigDecimal amount,
            BigDecimal settled,
            BigDecimal runningBalance,
            UUID documentId,
            String documentNumber,
            boolean limitBreached,
            boolean offline) {}
}
