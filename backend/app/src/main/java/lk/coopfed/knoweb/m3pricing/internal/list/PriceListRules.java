package lk.coopfed.knoweb.m3pricing.internal.list;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** Guards the four price-list handlers share. */
public final class PriceListRules {

    private PriceListRules() {}

    /**
     * The owner acts for its own entity, entity-wide (23A section 7: "owner"): a price list
     * belongs to the entity, not to a shop, and the read-only classes write nothing.
     */
    public static void requireOwnerScope(ScopeContext scope) {
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.locationId() != null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
    }

    /**
     * Today for a price list: the calendar date in the business time zone. A list belongs to an
     * entity and no location, and an entity has no business date of its own (BusinessDate,
     * CR-19A-8), as M1's relationships read it.
     */
    public static LocalDate today(Clock clock, ZoneId businessZone) {
        return LocalDate.ofInstant(clock.instant(), businessZone);
    }
}
