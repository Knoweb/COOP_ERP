package lk.coopfed.knoweb.m7customers.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /** What changed on the account's limits and state, newest first. */
    List<HistoryEntry> history(UUID accountId, ScopeContext scope);

    /** The account's adjustments, newest first. */
    List<Adjustment> adjustments(UUID accountId, ScopeContext scope);

    /**
     * @param origin     OFFICE (the society's ENTITY series) or TILL (the till's own series)
     * @param reversalOf the CPR this one reverses; null for a repayment
     * @param reversedBy the CPR that reversed this one; null while it stands
     */
    record Payment(
            UUID documentId,
            String docNumber,
            UUID accountId,
            BigDecimal amount,
            BigDecimal allocated,
            String origin,
            UUID reversalOf,
            UUID reversedBy) {}

    record Statement(
            UUID accountId,
            LocalDate from,
            LocalDate to,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            List<Line> lines) {}

    /**
     * One posting. For a CHARGE {@code settled} is what payments settled of it; for a PAYMENT it
     * is what the payment settled of the charges. {@code reversed}: a PAYMENT whose CPR was reversed.
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
            boolean offline,
            boolean reversed) {}

    /** One change of the account: LIMITS_AMENDED, SUSPENDED, REINSTATED or CLOSED. */
    record HistoryEntry(
            UUID historyId,
            String action,
            Map<String, Object> before,
            Map<String, Object> after,
            String reason,
            UUID changedBy,
            Instant changedAt) {}

    /** An adjustment: REQUESTED until another person approves it, then posted. */
    record Adjustment(
            UUID adjustmentId,
            BigDecimal amount,
            String reason,
            String status,
            UUID requestedBy,
            Instant requestedAt,
            UUID approvedBy,
            Instant approvedAt) {}
}
