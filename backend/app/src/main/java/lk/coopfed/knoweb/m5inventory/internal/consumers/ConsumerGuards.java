package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The guards the consumer-applied commands share. */
final class ConsumerGuards {

    private ConsumerGuards() {}

    /**
     * The event is applied in the OWN scope of the entity it concerns (the consumer framework
     * delivers it in the scope of the event's owner): the receiver of a GRN, the seller of a
     * delivery note. Anything else would write another entity's stock.
     */
    static void requireScopeOf(UUID entityId, ScopeContext scope, String messageId) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
        }
        if (entityId == null || !entityId.equals(scope.entityId())) {
            throw new ProblemException(messageId, Map.of("entityId", String.valueOf(entityId)));
        }
    }
}
