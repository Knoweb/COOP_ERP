package lk.coopfed.knoweb.m7customers.internal.customer;

import java.sql.Timestamp;
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
import lk.coopfed.knoweb.m7customers.api.ConsentRecorded;
import lk.coopfed.knoweb.m7customers.api.RecordConsent;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cus.customer.manage")
class RecordConsentHandler implements Handles<RecordConsent, UUID> {

    private final JdbcTemplate jdbc;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordConsentHandler(JdbcTemplate jdbc, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordConsent command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }

        CustomerGuards.requireOwnScope(scope);

        UUID customerId = command.customerId();
        String status = CustomerGuards.customerStatus(jdbc, customerId);
        if (!"ACTIVE".equals(status)) {
            throw new ProblemException("m7.customer.not_active");
        }

        String purpose = command.purpose();
        String via = command.via();

        if (purpose == null || !RegisterCustomerHandler.PURPOSES.contains(purpose)) {
            throw new ProblemException("m7.customer.consent_invalid");
        }
        if (via == null || !RegisterCustomerHandler.CHANNELS.contains(via)) {
            throw new ProblemException("m7.customer.via_invalid", Map.of("via", String.valueOf(via)));
        }

        Integer active = jdbc.queryForObject(
                """
                select count(*) from customers.customer_consent
                 where customer_id = ? and purpose = ?
                   and withdrawn_at is null
                """,
                Integer.class,
                customerId,
                purpose);

        if (active != null && active > 0) {
            throw new ProblemException("m7.customer.consent_already_active");
        }

        jdbc.update(
                """
                insert into customers.customer_consent
                    (consent_id, customer_id, purpose, granted_at,
                     granted_via, entity_id, owner_entity_id)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                Ids.next(),
                customerId,
                purpose,
                Timestamp.from(clock.now()),
                via,
                scope.entityId(),
                scope.entityId());

        audit.record(
                "CUSTOMER_CONSENT_RECORDED",
                Subject.of("customer", customerId),
                null,
                Map.of("purpose", purpose, "via", via),
                scope);

        events.publish(new ConsentRecorded(customerId, scope.entityId(), purpose));
        return customerId;
    }
}
