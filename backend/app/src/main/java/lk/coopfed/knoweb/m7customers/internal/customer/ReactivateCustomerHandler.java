package lk.coopfed.knoweb.m7customers.internal.customer;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.CustomerReactivated;
import lk.coopfed.knoweb.m7customers.api.ReactivateCustomer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReactivateCustomer (CUS-07). Guards, in order: the society's OWN scope; the customer registered
 * by the caller's society and INACTIVE (refuses ACTIVE with {@code m7.customer.already_active},
 * and ANONYMISED with {@code m7.customer.anonymised}); a reason free of a phone number or NIC.
 *
 * <p>Mutation: status ACTIVE. The customer re-enters the tills' snapshot and is immediately
 * eligible for credit purchasing. Audit CUSTOMER_REACTIVATED with the reason; event
 * customer.reactivated.v1.
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
class ReactivateCustomerHandler implements Handles<ReactivateCustomer, UUID> {

    static final String AUDIT_REACTIVATED = "CUSTOMER_REACTIVATED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    ReactivateCustomerHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ReactivateCustomer command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        UUID customerId = command.customerId();
        String currentStatus = CustomerGuards.customerStatus(jdbc, customerId);
        if ("ANONYMISED".equals(currentStatus)) {
            throw new ProblemException("m7.customer.anonymised");
        }
        if ("ACTIVE".equals(currentStatus)) {
            throw new ProblemException("m7.customer.already_active");
        }
        if (!"INACTIVE".equals(currentStatus)) {
            throw new ProblemException("m7.customer.status_invalid");
        }
        String reason = PersonalDataText.require(CustomerGuards.requiredText(command.reason(), "reason"), "reason");

        jdbc.update("update customers.customer set status = 'ACTIVE' where customer_id = ?", customerId);

        audit.record(
                AUDIT_REACTIVATED,
                Subject.of("customer", customerId),
                Map.of("status", "INACTIVE"),
                Map.of("status", "ACTIVE"),
                scope,
                reason);
        events.publish(new CustomerReactivated(customerId, scope.entityId()));
        return customerId;
    }
}
