package lk.coopfed.knoweb.m7customers.internal.account;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.AccountAdjusted;
import lk.coopfed.knoweb.m7customers.api.ApproveAdjustment;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger.LockedAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApproveAdjustment (27A section 6: "reason; SoD request ≠ approve; MFA"). Guards, in order: the
 * society's OWN scope with a user; the adjustment of this society ({@code m7.adjustment.not_found}),
 * still REQUESTED ({@code m7.adjustment.not_requested}); the approver is not the requester ({@code
 * m7.adjustment.same_person}: the INSTANCE rule of the pair cus.account.adjust /
 * cus.account.adjust_approve, held here whatever the pair table says); the account not CLOSED. The
 * second factor is the permission's ({@code cus.account.adjust_approve} requires MFA).
 *
 * <p>Mutation: the ADJUSTMENT posting (its document is the adjustment) on today's business date;
 * the adjustment APPROVED with who and when; the balance recomputed. Audit ACCOUNT_ADJUSTED;
 * event account.adjusted.v1.
 */
@Service
@CommandHandler(permission = "cus.account.adjust_approve")
class ApproveAccountAdjustmentHandler implements Handles<ApproveAdjustment, UUID> {

    static final String AUDIT_ADJUSTED = "ACCOUNT_ADJUSTED";

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ApproveAccountAdjustmentHandler(
            JdbcTemplate jdbc, Ledger ledger, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    private record Requested(UUID accountId, BigDecimal amount, String reason, String status, UUID requestedBy) {}

    @Override
    @Transactional
    public UUID handle(ApproveAdjustment command, ScopeContext scope) {
        if (command == null || command.adjustmentId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        if (scope.userId() == null) {
            throw new ProblemException("m7.adjustment.user_required");
        }
        Requested adjustment = jdbc
                .query(
                        """
                        select account_id, amount, reason, status, requested_by
                          from customers.account_adjustment where adjustment_id = ? for update
                        """,
                        (rs, n) -> new Requested(
                                rs.getObject("account_id", UUID.class),
                                rs.getBigDecimal("amount"),
                                rs.getString("reason"),
                                rs.getString("status"),
                                rs.getObject("requested_by", UUID.class)),
                        command.adjustmentId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException("m7.adjustment.not_found"));
        if (!"REQUESTED".equals(adjustment.status())) {
            throw new ProblemException("m7.adjustment.not_requested", Map.of("status", adjustment.status()));
        }
        if (scope.userId().equals(adjustment.requestedBy())) {
            throw new ProblemException("m7.adjustment.same_person");
        }
        LockedAccount account =
                ledger.lock(adjustment.accountId()).orElseThrow(() -> new ProblemException("m7.account.not_found"));
        if ("CLOSED".equals(account.status())) {
            throw new ProblemException("m7.account.closed");
        }

        UUID postingId = Ids.next();
        jdbc.update(
                """
                insert into customers.account_posting (posting_id, account_id, kind, amount, business_date, document_id,
                    operator_user_id, owner_entity_id)
                values (?, ?, 'ADJUSTMENT', ?, ?, ?, ?, ?)
                """,
                postingId,
                account.accountId(),
                adjustment.amount(),
                clock.today(),
                command.adjustmentId(),
                scope.userId(),
                account.ownerEntityId());
        jdbc.update(
                """
                update customers.account_adjustment
                   set status = 'APPROVED', approved_by = ?, approved_at = ?, posting_id = ?
                 where adjustment_id = ?
                """,
                scope.userId(),
                Timestamp.from(clock.now()),
                postingId,
                command.adjustmentId());
        BigDecimal balance = ledger.sum(account.accountId());
        jdbc.update(
                "update customers.customer_account set balance = ? where account_id = ?", balance, account.accountId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("adjustmentId", command.adjustmentId());
        after.put("postingId", postingId);
        after.put("amount", adjustment.amount());
        after.put("balance", balance);
        audit.record(
                AUDIT_ADJUSTED,
                Subject.of("customer_account", account.accountId()),
                Map.of("balance", account.balance()),
                after,
                scope,
                adjustment.reason());
        events.publish(new AccountAdjusted(
                account.accountId(),
                account.customerId(),
                account.ownerEntityId(),
                command.adjustmentId(),
                postingId,
                adjustment.amount(),
                balance));
        return postingId;
    }
}
