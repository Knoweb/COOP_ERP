package lk.coopfed.knoweb.m7customers.internal.customer;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.CustomerNicRecaptured;
import lk.coopfed.knoweb.m7customers.api.RecaptureNic;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecaptureNic (wave 2, M7CR-01; {@code 2026-10-06-wave2-keyed-hashes.md} (4)). Guards, in order:
 * the society's OWN scope; the customer registered by the caller's society and ACTIVE; a fresh
 * second factor, as a limit increase ({@code mfa.required}); a reason free of a phone number or
 * NIC; the NIC well formed and held by nobody else at any society ({@link NicCapture#recapture}:
 * no mismatch is asked, since the recorded one is what is being replaced).
 *
 * <p>Mutation: the keyed hash, the last four of the canonical form and the key id on the customer.
 * Audit NIC_RECAPTURED with {@code nicCaptured: true} and nothing else about the card; event
 * customer.nic_recaptured.v1.
 */
@Service
@CommandHandler(permission = "cus.account.manage")
class RecaptureNicHandler implements Handles<RecaptureNic, UUID> {

    static final String AUDIT_RECAPTURED = "NIC_RECAPTURED";

    private final JdbcTemplate jdbc;
    private final NicCapture nicCapture;
    private final ConfigRegistry config;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecaptureNicHandler(
            JdbcTemplate jdbc,
            NicCapture nicCapture,
            ConfigRegistry config,
            CustomersClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.nicCapture = nicCapture;
        this.config = config;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecaptureNic command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        UUID customerId = command.customerId();
        if (!"ACTIVE".equals(CustomerGuards.customerStatus(jdbc, customerId))) {
            throw new ProblemException("m7.customer.not_active");
        }
        CustomerGuards.requireFreshMfa(config, clock, scope, "cus.account.manage");
        String reason = PersonalDataText.require(CustomerGuards.requiredText(command.reason(), "reason"), "reason");
        NicCapture.Captured nic = nicCapture.recapture(command.nic(), customerId);

        jdbc.update(NicCapture.WRITE_SQL, nic.hash(), nic.last4(), nic.keyId(), customerId);

        audit.record(
                AUDIT_RECAPTURED, Subject.of("customer", customerId), null, Map.of("nicCaptured", true), scope, reason);
        events.publish(new CustomerNicRecaptured(customerId, scope.entityId()));
        return customerId;
    }
}
