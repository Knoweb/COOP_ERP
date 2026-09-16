package lk.coopfed.knoweb.kernel.api;

import java.util.Locale;
import java.util.UUID;

public record ScopeContext(
        UUID userId,
        UUID entityId,
        UUID locationId,
        PolicyClass policyClass,
        UUID correlationId,
        Locale locale) {

    public ScopeContext {
        if (policyClass == null) {
            policyClass = PolicyClass.NONE;
        }

        if (correlationId == null) {
            correlationId = Ids.next();
        }

        if (locale == null) {
            locale = Locale.ENGLISH;
        }
    }

    public static ScopeContext dev(UUID entityId, UUID locationId) {
        return new ScopeContext(
                Ids.next(),
                entityId,
                locationId,
                PolicyClass.OWN,
                Ids.next(),
                Locale.ENGLISH);
    }
}