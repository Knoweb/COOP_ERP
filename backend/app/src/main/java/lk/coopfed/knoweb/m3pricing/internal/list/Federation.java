package lk.coopfed.knoweb.m3pricing.internal.list;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which entity is the Federation ("caller F" in 23A section 7): the system entity of the
 * configuration (coop-erp.system.entity-id), as M2's FederationCaller reads it. Control prices,
 * ADVISORY lists and the Federation's MRP policy rows are its alone.
 */
@Component
public class Federation {

    private final Optional<UUID> federation;

    Federation(@Value("${coop-erp.system.entity-id:}") String federationEntityId) {
        this.federation = federationEntityId == null || federationEntityId.isBlank()
                ? Optional.empty()
                : Optional.of(UUID.fromString(federationEntityId.strip()));
    }

    /** The Federation's entity id; empty when the system entity is not configured. */
    public Optional<UUID> entityId() {
        return federation;
    }

    /** The caller acts for the Federation. */
    public boolean isCaller(ScopeContext scope) {
        return scope != null && federation.isPresent() && federation.get().equals(scope.entityId());
    }
}
