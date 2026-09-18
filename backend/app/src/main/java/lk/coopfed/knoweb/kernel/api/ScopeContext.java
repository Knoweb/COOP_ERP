package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The resolved principal and scope of one request: established at authentication,
 * carried unchanged into the database session, every event and every audit row
 * (doc 19 §1; 19A §1). Modules only read it; the kernel constructs it.
 *
 * @param userId          the authenticated user, or null for a device or system actor
 * @param deviceId        the enrolled device, when the caller is a till
 * @param homeEntityId    the entity the user belongs to (claim {@code ent})
 * @param scopes          every entity/location pair the principal may act in (claim {@code scopes})
 * @param activeScope     the pair chosen for this request via the X-Scope-Entity and
 *                        X-Scope-Location headers; defaults to the only scope when there is one
 * @param policyClass     the row-visibility class (claim {@code cls})
 * @param grantedEntities entities visible to an EXTERNAL_TIMEBOXED principal
 * @param mfaAt           when the second factor was last presented; null if never
 * @param locale          the interface language for this request
 * @param correlationId   groups everything one user action causes (claim {@code corr})
 */
public record ScopeContext(
        UUID userId,
        UUID deviceId,
        UUID homeEntityId,
        List<Scope> scopes,
        Scope activeScope,
        PolicyClass policyClass,
        Set<UUID> grantedEntities,
        Instant mfaAt,
        Locale locale,
        UUID correlationId) {

    public ScopeContext {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        grantedEntities = grantedEntities == null ? Set.of() : Set.copyOf(grantedEntities);

        if (activeScope == null && scopes.size() == 1) {
            activeScope = scopes.get(0);
        }
        if (policyClass == null) {
            policyClass = PolicyClass.NONE;
        }
        if (locale == null) {
            locale = Locale.ENGLISH;
        }
        if (correlationId == null) {
            correlationId = Ids.next();
        }
    }

    /** The entity chosen for this request; null when no scope is active, which fails closed under RLS. */
    public UUID entityId() {
        return activeScope == null ? null : activeScope.entityId();
    }

    /** The location chosen for this request; null for an entity-wide scope. */
    public UUID locationId() {
        return activeScope == null ? null : activeScope.locationId();
    }

    public boolean hasActiveScope() {
        return activeScope != null;
    }

    /** The two-letter language of {@link #locale()}: en, si or ta. */
    public String lang() {
        return locale.getLanguage();
    }

    /**
     * A single-scope OWN context for development and tests. The 17A stub builds these
     * from request headers; 19A replaces it with the token-based filter.
     */
    public static ScopeContext dev(
            UUID userId,
            UUID entityId,
            UUID locationId) {
        Scope scope = new Scope(entityId, locationId);

        return new ScopeContext(
                userId,
                null,
                entityId,
                List.of(scope),
                scope,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }
}
