package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The guard of the shared path (22A section 6: "F for shared"): creating, activating and
 * editing a SHARED SKU is the Federation's alone, in an entity-wide OWN scope. It is a guard and
 * not only the permission cat.sku.create, because it must also hold when permissions are not
 * enforced and for a command that does not arrive over HTTP (AGENTS.md).
 *
 * <p>M2 may not read M1's entity table, so the Federation is the entity that
 * {@code coop-erp.system.entity-id} names, as for the tax seeds (M2SeedLoader) and the
 * federation-wide configuration values of the kernel. When it is not set, nobody is the
 * Federation and the shared path is refused.
 */
@Component
class FederationCaller {

    static final String FEDERATION_ONLY = "m2.sku.federation_only";

    private final Optional<UUID> federation;

    FederationCaller(@Value("${coop-erp.system.entity-id:}") String federationEntityId) {
        this.federation = federationEntityId == null || federationEntityId.isBlank()
                ? Optional.empty()
                : Optional.of(UUID.fromString(federationEntityId.strip()));
    }

    void require(ScopeContext scope) {
        SkuGuards.requireEntityWideScope(scope);

        if (federation.isEmpty() || !federation.get().equals(scope.entityId())) {
            throw new ProblemException(FEDERATION_ONLY);
        }
    }
}
