package lk.coopfed.knoweb.m7customers.internal.privacy;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m7customers.api.DataSubjectRequestRefused;
import lk.coopfed.knoweb.m7customers.api.RefuseDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.customer.PersonalDataText;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RefuseRequest (27A section 6; doc 27 section 4.4: "legal ground recorded"). Guards, in order:
 * the society's OWN scope; the request of this society, RECEIVED; the caller is the society's
 * responsible officer; the legal ground ({@code m7.field.required}). The second factor is the
 * permission's ({@code cus.privacy.fulfil} requires MFA).
 *
 * <p>Mutation: the request REFUSED with who, when and the ground. Audit DSAR_REFUSED with the
 * ground as its reason; event dsar.refused.v1.
 */
@Service
@CommandHandler(permission = "cus.privacy.fulfil")
class RefuseDataSubjectRequestHandler implements Handles<RefuseDataSubjectRequest, UUID> {

    static final String AUDIT_REFUSED = "DSAR_REFUSED";

    private final JdbcTemplate jdbc;
    private final PartyQueries parties;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RefuseDataSubjectRequestHandler(
            JdbcTemplate jdbc, PartyQueries parties, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.parties = parties;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RefuseDataSubjectRequest command, ScopeContext scope) {
        if (command == null || command.requestId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        PrivacyGuards.Request request = PrivacyGuards.lockRequest(jdbc, command.requestId());
        if (!"RECEIVED".equals(request.status())) {
            throw new ProblemException("m7.privacy.not_received", Map.of("status", request.status()));
        }
        PrivacyGuards.requireResponsibleOfficer(parties, scope);
        String ground = PersonalDataText.require(CustomerGuards.requiredText(command.ground(), "ground"), "ground");

        jdbc.update(
                """
                update customers.data_subject_request
                   set status = 'REFUSED', fulfilled_at = ?, fulfilled_by = ?, refusal_ground = ?
                 where request_id = ?
                """,
                Timestamp.from(clock.now()),
                scope.userId(),
                ground,
                request.requestId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customerId", request.customerId());
        after.put("kind", request.kind());
        after.put("status", "REFUSED");
        audit.record(
                AUDIT_REFUSED,
                Subject.of("data_subject_request", request.requestId()),
                Map.of("status", "RECEIVED"),
                after,
                scope,
                ground);
        events.publish(new DataSubjectRequestRefused(
                request.requestId(), request.customerId(), request.ownerEntityId(), request.kind()));
        return request.requestId();
    }
}
