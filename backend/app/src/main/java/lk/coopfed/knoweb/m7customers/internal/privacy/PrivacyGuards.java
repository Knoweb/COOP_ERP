package lk.coopfed.knoweb.m7customers.internal.privacy;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.jdbc.core.JdbcTemplate;

/** The guards the privacy handlers share (doc 27 section 3.2; 27A section 6). */
final class PrivacyGuards {

    private PrivacyGuards() {}

    /** A request as the handlers read it, locked for the transaction. */
    record Request(UUID requestId, UUID customerId, String kind, String status, UUID ownerEntityId) {}

    static Request lockRequest(JdbcTemplate jdbc, UUID requestId) {
        return jdbc
                .query(
                        """
                        select request_id, customer_id, kind, status, owner_entity_id
                          from customers.data_subject_request where request_id = ? for update
                        """,
                        (rs, n) -> new Request(
                                rs.getObject("request_id", UUID.class),
                                rs.getObject("customer_id", UUID.class),
                                rs.getString("kind"),
                                rs.getString("status"),
                                rs.getObject("owner_entity_id", UUID.class)),
                        requestId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException("m7.privacy.not_found"));
    }

    /**
     * The responsible officer of the society answers its data-subject requests (doc 27 section
     * 3.2: "M1's per-entity officer is the addressee of data-subject requests for customers
     * registered by that entity"; 27A section 8: "Responsible officer only"). A society with no
     * officer appointed cannot answer one: M1 appoints them first.
     */
    static void requireResponsibleOfficer(PartyQueries parties, ScopeContext scope) {
        UUID officer = parties.getEntity(scope.entityId(), scope)
                .map(EntityView::responsibleOfficerUserId)
                .orElse(null);
        if (officer == null) {
            throw new ProblemException("m7.privacy.no_officer");
        }
        if (!officer.equals(scope.userId())) {
            throw new ProblemException("m7.privacy.officer_only", Map.of("officerUserId", officer.toString()));
        }
    }
}
