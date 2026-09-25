package lk.coopfed.knoweb.m1party.internal.relationship;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The guards the four relationship handlers share. Nothing here writes, audits or publishes:
 * those stay in the handlers, where the build's rules want them.
 */
final class RelationshipRules {

    /**
     * The far end of an open-ended range when two ranges are compared in Java. PostgreSQL's
     * {@code 'infinity'} has no {@link LocalDate}, and {@code LocalDate.MAX} is beyond the
     * largest date PostgreSQL stores.
     */
    static final LocalDate OPEN_END = LocalDate.of(9999, 12, 31);

    /** The allocation rules M4 knows (24A section 6.2); the V0007 check constraint says the same. */
    static final Set<String> ALLOCATION_RULES = Set.of("FCFS", "PRO_RATA", "QUOTA");

    /** SQLSTATE exclusion_violation: the A-I3 constraint refused a second ACTIVE row. */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private RelationshipRules() {}

    /**
     * The caller acts for its own entity, entity-wide: the relationship is the entity's, not a
     * shop's, and only the OWN class writes (doc 18 section 3.7). Returns that entity.
     */
    static UUID requireOwnEntityScope(ScopeContext scope) {
        if (!isOwnEntityScope(scope)) {
            throw new ProblemException("m1.relationship.seller_scope_required");
        }
        return scope.entityId();
    }

    private static boolean isOwnEntityScope(ScopeContext scope) {
        return scope != null
                && scope.hasActiveScope()
                && scope.entityId() != null
                && scope.locationId() == null
                && scope.policyClass() == PolicyClass.OWN;
    }

    /**
     * Only the seller changes a relationship (doc 21 section 4.2: every transition is the
     * seller's). The buyer reads the row (party_read) and must not get further; row-level
     * security would stop its update anyway, but with a database error instead of a reason.
     */
    static void requireSeller(ScopeContext scope, Relationship relationship) {
        if (!isOwnEntityScope(scope) || !scope.entityId().equals(relationship.sellerEntityId())) {
            throw new ProblemException("m1.relationship.not_seller", Map.of("relationshipId", relationship.getId()));
        }
    }

    static Relationship found(RelationshipRepository repository, UUID relationshipId) {
        if (relationshipId == null) {
            throw new ProblemException("request.field.required", Map.of("field", "relationshipId"));
        }
        return repository
                .findById(relationshipId)
                .orElseThrow(() ->
                        new ProblemException("m1.relationship.not_found", Map.of("relationshipId", relationshipId)));
    }

    static void requireReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new ProblemException("m1.relationship.reason_required");
        }
    }

    static String auditReason(String reasonCode, String reasonText) {
        String code = reasonCode.strip();
        if (reasonText == null || reasonText.isBlank()) {
            return code;
        }
        return code + ": " + reasonText.strip();
    }

    /**
     * No other ACTIVE row of the pair meets the range (A-I3). The pre-check of 21A section 6:
     * it names the row in the way, which the constraint's error cannot.
     *
     * @param exclude the row being activated or amended, which is not "another" row
     */
    static void requireNoOverlap(
            RelationshipRepository repository, UUID seller, UUID buyer, LocalDate from, LocalDate to, UUID exclude) {
        List<Relationship> overlapping =
                repository.activeOverlapping(seller, buyer, from, to == null ? OPEN_END : to, exclude);
        if (!overlapping.isEmpty()) {
            throw overlap(overlapping.get(0));
        }
    }

    /**
     * The backstop: two sellers' clerks activating at once both pass the pre-check, and the
     * constraint refuses the second. Its violation becomes the same problem as the pre-check's;
     * anything else is rethrown as it is.
     */
    static RuntimeException translate(DataIntegrityViolationException ex, Relationship relationship) {
        if (isExclusionViolation(ex)) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("relationshipId", relationship.getId());
            params.put("effectiveFrom", relationship.effectiveFrom().toString());
            return new ProblemException("m1.relationship.overlap", params);
        }
        return ex;
    }

    private static ProblemException overlap(Relationship existing) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("existingRelationshipId", existing.getId());
        params.put("effectiveFrom", existing.effectiveFrom().toString());
        return new ProblemException("m1.relationship.overlap", params);
    }

    private static boolean isExclusionViolation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
