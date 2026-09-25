package lk.coopfed.knoweb.kernel.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The change log of the shops' snapshots (doc 32 section 5.2; 19A section 8, "change log
 * producers"). Any publication that changes what a shop's till holds (a price list published,
 * a rule activated, a control price entered, a SKU shared, an operator added) records here, in
 * the publishing transaction, which rows of which snapshot table changed at which locations.
 * Each call bumps each named location's snapshot version by one, so a till that downloads the
 * delta since its version gets the whole publication or none of it (doc 32 S5).
 *
 * <p>The producers are the modules' snapshot contributors (M1-10, M2-09 and the rest); the till
 * reads the log through the sync API. The kernel writes the rows whatever the caller's entity:
 * a central change fans out to shops of many entities. A producer that would write thousands of
 * rows (a SKU shared with every shop) does so from a worker consumer of its own event, not on the
 * hot path (19A section 15, "snapshot fan-out").
 */
public interface ChangeLog {

    /** What happened to a row of a snapshot table. */
    enum Op {
        UPSERT,
        DELETE
    }

    /** One changed row: the snapshot table (sku, price_list_line, operator ...) and the row's id. */
    record Change(String table, UUID rowId, Op op) {

        public Change {
            if (table == null || table.isBlank() || table.length() > 48) {
                throw new IllegalArgumentException("A change names its snapshot table, at most 48 characters");
            }
            if (rowId == null || op == null) {
                throw new IllegalArgumentException("A change names its row and what happened to it");
            }
        }

        public static Change upsert(String table, UUID rowId) {
            return new Change(table, rowId, Op.UPSERT);
        }

        public static Change delete(String table, UUID rowId) {
            return new Change(table, rowId, Op.DELETE);
        }
    }

    /** A shop whose snapshot the publication changes, with the entity that owns it. */
    record Target(UUID ownerEntityId, UUID locationId) {

        public Target {
            if (ownerEntityId == null || locationId == null) {
                throw new IllegalArgumentException("A change-log target is a location of an entity");
            }
        }
    }

    /**
     * Records one publication at every target, inside the caller's transaction.
     *
     * @param applyFrom the business date from which the tills apply the rows (doc 32 section 5.2:
     *                  "held in pending and activated at the shop's day-open"); null for at once
     * @param urgent    the tills download at their next heartbeat rather than within the usual
     *                  fifteen minutes (a control price entered, a permission revoked)
     * @return the new snapshot version of each target location
     * @throws IllegalStateException outside a transaction
     */
    Map<UUID, Long> append(
            Collection<Target> targets, List<Change> changes, LocalDate applyFrom, boolean urgent, ScopeContext ctx);

    /** One location, for the common case. */
    default long append(Target target, List<Change> changes, LocalDate applyFrom, boolean urgent, ScopeContext ctx) {
        return append(List.of(target), changes, applyFrom, urgent, ctx).get(target.locationId());
    }
}
