package lk.coopfed.knoweb.m7customers.internal.customer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.CustomerDeactivated;
import lk.coopfed.knoweb.m7customers.api.DeactivateCustomer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DeactivateCustomer (27A section 6). Guards, in order: the society's OWN scope; the customer
 * registered by the caller's society and ACTIVE; a reason free of a phone number or NIC; no OPEN
 * or SUSPENDED account of the caller's society with a balance other than zero ({@code
 * m7.customer.open_balance}).
 *
 * <p>Mutation: status INACTIVE. The account and its postings are untouched (27A); the customer
 * leaves the tills' snapshot, which holds ACTIVE customers only, and a till charge that still
 * arrives is posted and flagged (PostAccountTender, ACCOUNT_CHARGED_CUSTOMER_INACTIVE).
 * Reactivation is not built (README, "Deferred"). Audit CUSTOMER_DEACTIVATED with the reason;
 * event customer.deactivated.v1.
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
class DeactivateCustomerHandler implements Handles<DeactivateCustomer, UUID> {

    static final String AUDIT_DEACTIVATED = "CUSTOMER_DEACTIVATED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    DeactivateCustomerHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(DeactivateCustomer command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        UUID customerId = command.customerId();
        if (!"ACTIVE".equals(CustomerGuards.customerStatus(jdbc, customerId))) {
            throw new ProblemException("m7.customer.not_active");
        }
        String reason = PersonalDataText.require(CustomerGuards.requiredText(command.reason(), "reason"), "reason");
        // Wave 2 (M7CR-05): a SUSPENDED debtor is a debtor too.
        List<BigDecimal> balances = jdbc.queryForList(
                """
                select balance from customers.customer_account
                 where customer_id = ? and status in ('OPEN', 'SUSPENDED') and balance <> 0 for update
                """,
                BigDecimal.class,
                customerId);
        if (!balances.isEmpty()) {
            throw new ProblemException(
                    "m7.customer.open_balance",
                    Map.of("balance", balances.get(0).toPlainString()));
        }

        jdbc.update("update customers.customer set status = 'INACTIVE' where customer_id = ?", customerId);

        // The reason travels as the audit's reason, never in after (wave 2, M7CR-10).
        audit.record(
                AUDIT_DEACTIVATED,
                Subject.of("customer", customerId),
                Map.of("status", "ACTIVE"),
                Map.of("status", "INACTIVE"),
                scope,
                reason);
        events.publish(new CustomerDeactivated(customerId, scope.entityId()));
        return customerId;
    }
}
