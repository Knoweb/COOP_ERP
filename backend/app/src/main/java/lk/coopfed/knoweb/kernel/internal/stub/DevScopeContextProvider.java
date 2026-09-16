package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

@Component
public class DevScopeContextProvider {

    public ScopeContext fromHeaders(
            String userId,
            String entityId,
            String locationId,
            String policyClass,
            String correlationId,
            String language) {
        return new ScopeContext(
                parseUuid(userId, Ids.next()),
                parseUuid(entityId, null),
                parseUuid(locationId, null),
                parsePolicy(policyClass),
                parseUuid(correlationId, Ids.next()),
                language == null || language.isBlank()
                        ? Locale.ENGLISH
                        : Locale.forLanguageTag(language));
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