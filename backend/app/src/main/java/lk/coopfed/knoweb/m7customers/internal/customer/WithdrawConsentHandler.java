package lk.coopfed.knoweb.m7customers.internal.customer;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.ConsentWithdrawn;
import lk.coopfed.knoweb.m7customers.api.WithdrawConsent;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cus.customer.manage")
class WithdrawConsentHandler implements Handles<WithdrawConsent, UUID> {

    private final JdbcTemplate jdbc;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    WithdrawConsentHandler(JdbcTemplate jdbc, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(WithdrawConsent command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }

        CustomerGuards.requireOwnScope(scope);

        UUID customerId = command.customerId();
        CustomerGuards.customerStatus(jdbc, customerId);

        String purpose = command.purpose();
        if (purpose == null || !RegisterCustomerHandler.PURPOSES.contains(purpose)) {
            throw new ProblemException("m7.customer.consent_invalid");
        }

        int updated = jdbc.update(
                """
                update customers.customer_consent
                   set withdrawn_at = ?
                 where customer_id = ? and purpose = ?
                   and withdrawn_at is null
                """,
                Timestamp.from(clock.now()),
                customerId,
                purpose);

        if (updated == 0) {
            throw new ProblemException("m7.customer.consent_not_active");
        }

        audit.record(
                "CUSTOMER_CONSENT_WITHDRAWN",
                Subject.of("customer", customerId),
                Map.of("purpose", purpose),
                Map.of("withdrawn", true),
                scope);

        events.publish(new ConsentWithdrawn(customerId, scope.entityId(), purpose));
        return customerId;
    }
}
