package lk.coopfed.knoweb.m6pos.internal.ingest;

import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The guard both ingest handlers share. */
final class IngestGuards {

    private IngestGuards() {}

    /**
     * A till's fact is applied in its device's OWN scope at its shop (EventConsumerDispatcher gives
     * the event's owner and location): the rows are the shop's.
     */
    static void requireDeviceScope(ScopeContext scope) {
        if (scope == null
                || scope.policyClass() != PolicyClass.OWN
                || scope.entityId() == null
                || scope.locationId() == null) {
            throw new ProblemException("m6.scope.device_required");
        }
    }
}
