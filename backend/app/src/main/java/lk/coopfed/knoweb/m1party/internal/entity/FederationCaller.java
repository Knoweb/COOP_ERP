package lk.coopfed.knoweb.m1party.internal.entity;

import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The guard the entity lifecycle commands share: registering, activating, suspending and
 * reinstating an entity are the Federation's to do (21A section 6, FEDERATION scope), in an
 * entity-wide OWN scope. It is a guard and not only a permission, because it must also hold
 * for a command that does not arrive over HTTP (AGENTS.md), and because row-level security
 * alone would let an entity suspend or activate itself.
 */
final class FederationCaller {

    private FederationCaller() {}

    /** Returns the calling Federation entity, or refuses with {@code m1.entity.federation_required}. */
    static Entity require(ScopeContext scope, EntityRepository repository) {
        return require(scope, repository, "m1.entity.federation_required");
    }

    /** The same guard, refusing with the given message id: the grant commands say what they grant. */
    static Entity require(ScopeContext scope, EntityRepository repository, String problem) {
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.locationId() != null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException(problem);
        }
        Entity caller = repository.findById(scope.entityId()).orElseThrow(() -> new ProblemException(problem));
        if (!caller.isFederation()) {
            throw new ProblemException(problem);
        }
        return caller;
    }
}
