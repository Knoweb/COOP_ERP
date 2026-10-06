package lk.coopfed.knoweb.m7customers.internal.customer;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
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
import lk.coopfed.knoweb.m7customers.api.ChangePhone;
import lk.coopfed.knoweb.m7customers.api.CustomerPhoneChanged;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChangePhone (27A section 6). Guards, in order: the society's OWN scope; the customer registered
 * by the caller's society and ACTIVE; the new phone a Sri Lankan number, and not the current one;
 * a reason free of a phone number or NIC; the reuse detection of section 6.1 for the new number
 * passed or confirmed.
 *
 * <p>Mutation: the current primary row closed (valid_to), a new primary row. The old number stays
 * in the history, which is what reuse detection reads. Audit CUSTOMER_PHONE_CHANGED with the
 * reason as its reason and, when confirmed, the previous holders the caller's society registered
 * by id plus a count of the others (never a number); event customer.phone_changed.v1.
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
class ChangePhoneHandler implements Handles<ChangePhone, UUID> {

    static final String AUDIT_CHANGED = "CUSTOMER_PHONE_CHANGED";

    private final JdbcTemplate jdbc;
    private final ReuseDetector reuse;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ChangePhoneHandler(
            JdbcTemplate jdbc, ReuseDetector reuse, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.reuse = reuse;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ChangePhone command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        UUID customerId = command.customerId();
        if (!"ACTIVE".equals(CustomerGuards.customerStatus(jdbc, customerId))) {
            throw new ProblemException("m7.customer.not_active");
        }
        String phone = PhoneNumbers.normalise(command.newPhone())
                .orElseThrow(() -> new ProblemException("m7.customer.phone_invalid"));
        List<String> current = jdbc.queryForList(
                """
                select phone from customers.customer_phone
                 where customer_id = ? and is_primary and valid_to is null for update
                """,
                String.class,
                customerId);
        if (current.contains(phone)) {
            throw new ProblemException("m7.customer.phone_unchanged");
        }
        String reason = PersonalDataText.require(CustomerGuards.requiredText(command.reason(), "reason"), "reason");
        ReuseDetector.Confirmed previousHolders = reuse.guard(phone, customerId, command.confirmedIdentity(), scope);

        Timestamp now = Timestamp.from(clock.now());
        jdbc.update(
                """
                update customers.customer_phone set valid_to = ?
                 where customer_id = ? and is_primary and valid_to is null
                """,
                now,
                customerId);
        jdbc.update(
                """
                insert into customers.customer_phone (phone_id, customer_id, phone, is_primary, valid_from, changed_by,
                    reason, owner_entity_id)
                values (?, ?, ?, true, ?, ?, ?, ?)
                """,
                Ids.next(),
                customerId,
                phone,
                now,
                scope.userId(),
                reason,
                scope.entityId());

        // The reason travels as the audit's reason, never in after (wave 2, M7CR-10); the
        // previous holders as the caller's own ids plus a count of the others (RLS-03).
        Map<String, Object> after = new LinkedHashMap<>();
        previousHolders.describe(after);
        audit.record(AUDIT_CHANGED, Subject.of("customer", customerId), null, after, scope, reason);
        events.publish(new CustomerPhoneChanged(customerId, scope.entityId()));
        return customerId;
    }
}
