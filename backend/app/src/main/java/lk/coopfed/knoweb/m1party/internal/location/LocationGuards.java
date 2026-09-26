package lk.coopfed.knoweb.m1party.internal.location;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The guards the location and position handlers share. Each throws the problem the handler
 * specification names (21A section 6); a handler calls them in the order its specification
 * lists.
 */
final class LocationGuards {

    private LocationGuards() {}

    /**
     * Every location command writes, and only an OWN scope writes (doc 18 section 3.7). Without
     * this a FEDERATION_VIEW caller would read the row through fed_view and fail on the update
     * with a database error instead of a message.
     */
    static void requireOwnScope(ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope() || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("m1.location.scope_required");
        }
    }

    /**
     * A location is registered from the entity-wide scope: a user scoped to one shop registers
     * no other place of the entity. Row-level security lets such a caller insert (own_write has
     * no location clause, since a new location has no id the scope could name), so the guard
     * is what refuses it.
     */
    static void requireEntityWideScope(ScopeContext scope) {
        requireOwnScope(scope);
        if (scope.locationId() != null) {
            throw new ProblemException("m1.location.entity_scope_required");
        }
    }

    /** The location, if the caller's scope sees it; row-level security decides which it sees. */
    static Location location(LocationRepository locations, UUID locationId) {
        return locations
                .findById(locationId)
                .orElseThrow(() -> new ProblemException(
                        "m1.location.not_found", Map.of("locationId", String.valueOf(locationId))));
    }

    static TillPosition position(TillPositionRepository positions, UUID tillPositionId) {
        return positions
                .findById(tillPositionId)
                .orElseThrow(() -> new ProblemException(
                        "m1.position.not_found", Map.of("tillPositionId", String.valueOf(tillPositionId))));
    }

    /**
     * The location, locked until the transaction ends: for a handler that changes the location
     * or one of its positions, so that two such commands on one shop run one after the other
     * (the review of M1-05: SetPrimaryTill and RetireTillPosition passed each other).
     */
    static Location lockedLocation(LocationRepository locations, UUID locationId) {
        return locations
                .findByIdForUpdate(locationId)
                .orElseThrow(() -> new ProblemException(
                        "m1.location.not_found", Map.of("locationId", String.valueOf(locationId))));
    }

    /** The position, locked until the transaction ends; taken after the location's lock. */
    static TillPosition lockedPosition(TillPositionRepository positions, UUID tillPositionId) {
        return positions
                .findByIdForUpdate(tillPositionId)
                .orElseThrow(() -> new ProblemException(
                        "m1.position.not_found", Map.of("tillPositionId", String.valueOf(tillPositionId))));
    }

    /** Refuses a move the state machine of doc 21 section 4.3 does not have. */
    static void requireStatus(Location location, String expected, String target) {
        if (!expected.equals(location.status())) {
            throw new ProblemException(
                    "m1.location.transition_invalid", Map.of("status", location.status(), "target", target));
        }
    }

    /** The reason the audit record carries: the code, and the text after it when there is one. */
    static String reason(String reasonCode, String reasonText) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new ProblemException("m1.location.reason_required");
        }
        String code = reasonCode.strip();
        return reasonText == null || reasonText.isBlank() ? code : code + ": " + reasonText.strip();
    }
}
