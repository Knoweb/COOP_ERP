package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 17A stub: a fixed development scope built from request headers. 19A replaces it with
 * the token-based scope filter. A request without an entity header gets no active
 * scope, so row-level security returns nothing: fail closed, as in production.
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
        Scope scope = entity == null
                ? null
                : new Scope(entity, parseUuid(locationId, null));

        return new ScopeContext(
                parseUuid(userId, Ids.next()),
                null,
                entity,
                scope == null ? List.of() : List.of(scope),
                scope,
                scope == null ? PolicyClass.NONE : parsePolicy(policyClass),
                Set.of(),
                null,
                language == null || language.isBlank()
                        ? Locale.ENGLISH
                        : Locale.forLanguageTag(language),
                parseUuid(correlationId, Ids.next()));
    }

    private UUID parseUuid(
            String value,
            UUID fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return UUID.fromString(value);
    }

    private PolicyClass parsePolicy(
            String value) {
        if (value == null || value.isBlank()) {
            return PolicyClass.OWN;
        }

        return PolicyClass.valueOf(
                value.trim().toUpperCase(Locale.ROOT));
    }
}
