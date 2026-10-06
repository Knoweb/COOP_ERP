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
import lk.coopfed.knoweb.m7customers.api.AdjustmentRequested;
import lk.coopfed.knoweb.m7customers.api.RequestAdjustment;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.customer.PersonalDataText;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger.LockedAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostAdjustment, the request (27A section 6: "adjustment document; ADJUSTMENT posting on
 * approval"). Guards, in order: the society's OWN scope with a user (the approver must be
 * another person, so the requester is named: {@code m7.adjustment.user_required}); the account of
 * this society, not CLOSED; an amount other than zero in cents ({@code m7.adjustment.amount_invalid});
 * a reason free of a phone number or NIC ({@code m7.field.personal_data}: the reason is retained
 * after an erasure).
 *
 * <p>Mutation: the adjustment, REQUESTED; nothing is posted until another person approves it.
 * Audit ADJUSTMENT_REQUESTED; event account.adjusted.v1 comes with the approval. The request
 * publishes {@link AdjustmentRequested} so a reviewer's list can follow it.
 */
@Service
@CommandHandler(permission = "cus.account.adjust")
class RequestAdjustmentHandler implements Handles<RequestAdjustment, UUID> {

    static final String AUDIT_REQUESTED = "ADJUSTMENT_REQUESTED";

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RequestAdjustmentHandler(
            JdbcTemplate jdbc, Ledger ledger, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RequestAdjustment command, ScopeContext scope) {
        if (command == null || command.accountId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        if (scope.userId() == null) {
            throw new ProblemException("m7.adjustment.user_required");
        }
        LockedAccount account =
                ledger.lock(command.accountId()).orElseThrow(() -> new ProblemException("m7.account.not_found"));
        if ("CLOSED".equals(account.status())) {
            throw new ProblemException("m7.account.closed");
        }
        BigDecimal amount = command.amount();
        if (amount == null
                || amount.signum() == 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new ProblemException("m7.adjustment.amount_invalid");
        }
        amount = amount.setScale(2);
        String reason = PersonalDataText.require(CustomerGuards.requiredText(command.reason(), "reason"), "reason");

        UUID adjustmentId = Ids.next();
        jdbc.update(
                """
                insert into customers.account_adjustment (adjustment_id, account_id, amount, reason, status, requested_by,
                    requested_at, owner_entity_id)
                values (?, ?, ?, ?, 'REQUESTED', ?, ?, ?)
                """,
                adjustmentId,
                account.accountId(),
                amount,
                reason,
                scope.userId(),
                Timestamp.from(clock.now()),
                account.ownerEntityId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("accountId", account.accountId());
        after.put("amount", amount);
        after.put("status", "REQUESTED");
        audit.record(AUDIT_REQUESTED, Subject.of("account_adjustment", adjustmentId), null, after, scope, reason);
        events.publish(new AdjustmentRequested(adjustmentId, account.accountId(), account.ownerEntityId(), amount));
        return adjustmentId;
    }
}
