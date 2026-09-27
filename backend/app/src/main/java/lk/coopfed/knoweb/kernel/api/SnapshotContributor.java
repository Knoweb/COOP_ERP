package lk.coopfed.knoweb.kernel.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a module gives the till snapshot of a shop (doc 32 section 5.1; 19A section 8, "snapshot
 * builder"): the current rows of the snapshot tables it owns. The kernel's snapshot builder asks
 * every contributor, puts the rows beside the change log and serves them through
 * {@code GET /v1/sync/locations/{id}/snapshot}. M1 contributes the shop, its till positions and
 * its operators; M2 the catalogue; M3, M5 and M7 add theirs when they are built.
 *
 * <p>A contributor only reads. The builder calls it inside one read-only, repeatable-read
 * transaction in the scope of the device that asks (its entity and its shop), so the rows it
 * returns are exactly those at the snapshot version the builder read, and row-level security
 * applies as for any read of that device.
 *
 * <p>A row is a map of column name (snake_case) to value, and the value is one of: text, a whole
 * number, a boolean, null, or a list or map of those. A decimal (a price, a rate, a factor) is
 * sent as text ("12.50"): the till must not round it, and the manifest hash is taken over the
 * text. A date is ISO text ("2026-10-01").
 */
public interface SnapshotContributor {

    /** The shop a snapshot is built for. */
    record Shop(UUID ownerEntityId, UUID locationId) {}

    /**
     * The snapshot tables this contributor serves (lower case, as the change log names them:
     * "sku", "operator" ...). Each table has exactly one contributor; the builder refuses to start
     * when two claim the same one.
     */
    Set<String> tables();

    /**
     * The rows among {@code rowIds} that are in the shop's snapshot now, by row id. An id left out
     * of the answer has left the snapshot (the SKU went inactive, the operator lost the shop): the
     * builder sends the till a tombstone for it.
     */
    Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds);

    /** Every row of the table in the shop's snapshot now, for the full snapshot. */
    Map<UUID, Map<String, Object>> allRows(String table, Shop shop);
}
