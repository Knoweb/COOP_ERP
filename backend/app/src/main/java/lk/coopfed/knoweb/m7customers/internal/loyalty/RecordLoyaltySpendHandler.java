package lk.coopfed.knoweb.m7customers.internal.loyalty;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import lk.coopfed.knoweb.m7customers.api.LoyaltySpendRecorded;
import lk.coopfed.knoweb.m7customers.api.RecordLoyaltySpend;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles recording customer spend transactions for monthly loyalty qualification (PGL-M3-03).
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
public class RecordLoyaltySpendHandler implements Handles<RecordLoyaltySpend, UUID> {

    public static final String AUDIT_SPEND_RECORDED = "LOYALTY_SPEND_RECORDED";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    public RecordLoyaltySpendHandler(JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordLoyaltySpend command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        if (scope == null || scope.entityId() == null) {
            throw new ProblemException("m7.scope.own_required");
        }
        if (command.loyaltyId() == null) {
            throw new ProblemException("m7.loyalty.not_found");
        }
        if (command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ProblemException("m7.loyalty.spend_amount_invalid");
        }
        LocalDate spendDate = command.spendDate() != null ? command.spendDate() : LocalDate.now(clock);

        UUID ownerEntityId = scope.entityId();

        // Verify loyalty customer existence and active status
        Map<String, Object> customer = jdbc.query(
                "SELECT customer_id, status FROM customers.loyalty_customer WHERE loyalty_id = ? AND owner_entity_id = ?",
                rs -> rs.next()
                        ? Map.of("customer_id", rs.getObject("customer_id"), "status", rs.getString("status"))
                        : null,
                command.loyaltyId(),
                ownerEntityId);

        if (customer == null) {
            throw new ProblemException("m7.loyalty.not_found");
        }
        if (!"ACTIVE".equals(customer.get("status"))) {
            throw new ProblemException("m7.customer.not_active");
        }

        UUID customerId = (UUID) customer.get("customer_id");
        UUID locationId = command.locationId() != null
                ? command.locationId()
                : (scope.locationId() != null ? scope.locationId() : ownerEntityId);
        UUID spendId = Ids.next();
        Instant now = clock.instant();

        jdbc.update(
                """
                INSERT INTO customers.loyalty_spend
                    (spend_id, owner_entity_id, loyalty_id, customer_id, location_id,
                     spend_date, amount, receipt_number, recorded_at, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                spendId,
                ownerEntityId,
                command.loyaltyId(),
                customerId,
                locationId,
                Date.valueOf(spendDate),
                command.amount(),
                command.receiptNumber(),
                Timestamp.from(now),
                scope.userId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("spend_id", spendId.toString());
        after.put("loyalty_id", command.loyaltyId().toString());
        after.put("amount", command.amount().toPlainString());
        after.put("spend_date", spendDate.toString());

        audit.record(AUDIT_SPEND_RECORDED, Subject.of("loyalty_spend", spendId), null, after, scope);

        events.publish(new LoyaltySpendRecorded(
                spendId, ownerEntityId, command.loyaltyId(), customerId, locationId, spendDate, command.amount(), now));

        return spendId;
    }
}
