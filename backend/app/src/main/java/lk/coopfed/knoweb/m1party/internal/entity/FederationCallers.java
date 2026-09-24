package lk.coopfed.knoweb.m1party.internal.entity;

import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * {@link FederationCaller} as a bean, for handlers outside this package (the bulk registration
 * in {@code internal.bulk}) that need the same guard without seeing the entity repository.
 */
@Component
public class FederationCallers {

    private final EntityRepository repository;

    FederationCallers(EntityRepository repository) {
        this.repository = repository;
    }

    /** Refuses with {@code m1.entity.federation_required} unless the caller is the Federation in an entity-wide OWN scope. */
    public void require(ScopeContext scope) {
        FederationCaller.require(scope, repository);
    }
}
