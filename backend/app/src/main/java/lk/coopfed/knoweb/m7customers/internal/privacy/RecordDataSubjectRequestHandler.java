package lk.coopfed.knoweb.m7customers.internal.privacy;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.DataSubjectRequestReceived;
import lk.coopfed.knoweb.m7customers.api.RecordDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordDataSubjectRequest (27A section 6; doc 27 section 4.4: "customer identified; received by
 * a named officer"). Guards, in order: the society's OWN scope with a user (the person who received
 * the request is named: {@code m7.privacy.user_required}); a known kind ({@code
 * m7.privacy.kind_invalid}); the customer, registered by this society ({@code
 * m7.privacy.not_registering_society}: the registering society's officer is the addressee, doc 27
 * section 3.2); not already anonymised ({@code m7.customer.anonymised}); no request of the same
 * kind still open for the customer ({@code m7.privacy.request_open}).
 *
 * <p>Mutation: the request, RECEIVED. Audit DSAR_RECEIVED with the kind (never the notes, which may
 * name the person); event dsar.received.v1.
 */
@Service
@CommandHandler(permission = "cus.privacy.record")
class RecordDataSubjectRequestHandler implements Handles<RecordDataSubjectRequest, UUID> {

    static final String AUDIT_RECEIVED = "DSAR_RECEIVED";
    static final Set<String> KINDS = Set.of(
            RecordDataSubjectRequest.ACCESS, RecordDataSubjectRequest.CORRECTION, RecordDataSubjectRequest.ERASURE);

    private final JdbcTemplate jdbc;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordDataSubjectRequestHandler(JdbcTemplate jdbc, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordDataSubjectRequest command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        if (scope.userId() == null) {
            throw new ProblemException("m7.privacy.user_required");
        }
        if (!KINDS.contains(command.kind())) {
            throw new ProblemException("m7.privacy.kind_invalid", Map.of("kind", String.valueOf(command.kind())));
        }
        record Customer(UUID owner, String status) {}
        List<Customer> found = jdbc.query(
                "select owner_entity_id, status from customers.customer where customer_id = ? for update",
                (rs, n) -> new Customer(rs.getObject("owner_entity_id", UUID.class), rs.getString("status")),
                command.customerId());
        if (found.isEmpty()) {
            throw new ProblemException("m7.customer.not_found");
        }
        if (!scope.entityId().equals(found.get(0).owner())) {
            throw new ProblemException("m7.privacy.not_registering_society");
        }
        if ("ANONYMISED".equals(found.get(0).status())) {
            throw new ProblemException("m7.customer.anonymised");
        }
        Integer open = jdbc.queryForObject(
                """
                select count(*) from customers.data_subject_request
                 where customer_id = ? and kind = ? and status = 'RECEIVED'
                """,
                Integer.class,
                command.customerId(),
                command.kind());
        if (open != null && open > 0) {
            throw new ProblemException("m7.privacy.request_open");
        }

        UUID requestId = Ids.next();
        jdbc.update(
                """
                insert into customers.data_subject_request (request_id, customer_id, kind, notes, received_at, received_by,
                    status, owner_entity_id)
                values (?, ?, ?, ?, ?, ?, 'RECEIVED', ?)
                """,
                requestId,
                command.customerId(),
                command.kind(),
                CustomerGuards.blankToNull(command.notes()),
                Timestamp.from(clock.now()),
                scope.userId(),
                scope.entityId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customerId", command.customerId());
        after.put("kind", command.kind());
        after.put("status", "RECEIVED");
        audit.record(AUDIT_RECEIVED, Subject.of("data_subject_request", requestId), null, after, scope);
        events.publish(
                new DataSubjectRequestReceived(requestId, command.customerId(), scope.entityId(), command.kind()));
        return requestId;
    }
}
