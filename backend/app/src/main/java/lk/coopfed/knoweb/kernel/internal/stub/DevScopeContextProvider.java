package lk.coopfed.knoweb.kernel.internal.stub;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * 17A stub: a development scope built from request headers, for a request that presents no
 * token (K-02, first pull request; the second deletes this class). A request without an
 * entity header gets no active scope, so row-level security returns nothing, and one without
 * a user header gets no user, so no command runs: fail closed, as in production.
 */
@Component
public class DevScopeContextProvider {

    public ScopeContext fromHeaders(
            String userId,
            String entityId,
            String locationId,
            String policyClass,
            String correlationId,
            String language) {
        UUID entity = parseUuid(entityId, null);
        Scope scope = entity == null ? null : new Scope(entity, parseUuid(locationId, null));

        return new ScopeContext(
                parseUuid(userId, null),
                null,
                entity,
                scope == null ? List.of() : List.of(scope),
                scope,
                scope == null ? PolicyClass.NONE : parsePolicy(policyClass),
                Set.of(),
                null,
                language == null || language.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(language),
                parseUuid(correlationId, Ids.next()));
    }

    private UUID parseUuid(String value, UUID fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return UUID.fromString(value);
    }

    private PolicyClass parsePolicy(String value) {
        if (value == null || value.isBlank()) {
            return PolicyClass.OWN;
        }

        return PolicyClass.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
