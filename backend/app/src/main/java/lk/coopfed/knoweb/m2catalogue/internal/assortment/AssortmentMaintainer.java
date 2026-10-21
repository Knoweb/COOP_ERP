package lk.coopfed.knoweb.m2catalogue.internal.assortment;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.AssortmentDriftDetected;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.api.SkuActivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuDeactivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuMerged;
import lk.coopfed.knoweb.m2catalogue.api.SkuReactivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuUpdated;
import lk.coopfed.knoweb.m2catalogue.internal.snapshot.CatalogueShops;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assortment maintainer (22A section 7.2; doc 22 section 3.8).
 *
 * <p>Maintains {@code catalogue.location_assortment} by listening to price-list, stock, and SKU
 * lifecycle domain events. Ensures each till receives strictly only its designated assortment and
 * supports sell-through for deactivated SKUs with remaining lots.
 */
@Component
public class AssortmentMaintainer {

    private static final Logger log = LoggerFactory.getLogger(AssortmentMaintainer.class);

    public static final String CONSUMER = "m2.assortment";
    public static final String SKU_TABLE = "sku";

    public static final String REASON_RETAIL_LIST = "RETAIL_LIST";
    public static final String REASON_LOT = "LOT";
    public static final String REASON_LOCAL = "LOCAL";
    public static final String REASON_CORE_RANGE = "CORE_RANGE";

    private final AssortmentStore store;
    private final CatalogueShops shops;
    private final ChangeLog changeLog;
    private final InventoryLotQuery inventory;
    private final JdbcTemplate jdbc;

    public AssortmentMaintainer(
            AssortmentStore store,
            CatalogueShops shops,
            ChangeLog changeLog,
            InventoryLotQuery inventory,
            JdbcTemplate jdbc) {
        this.store = store;
        this.shops = shops;
        this.changeLog = changeLog;
        this.inventory = inventory;
        this.jdbc = jdbc;
    }

    // ---- Assortment Writer Event Consumer -----------------------------------------------------

    @EventConsumer(
            types = {AssortmentDriftDetected.TYPE},
            consumer = "m2.assortment-drift")
    @Transactional
    public void onAssortmentDrift(AssortmentDriftDetected event, ScopeContext scope) {
        if (event.action() == AssortmentDriftDetected.Action.DELETE) {
            jdbc.update(
                    "DELETE FROM catalogue.location_assortment WHERE location_id = ? AND sku_id = ?",
                    event.locationId(),
                    event.skuId());
        } else {
            String sql =
                    """
                    INSERT INTO catalogue.location_assortment (
                        location_id, sku_id, reasons, sell_through, since, owner_entity_id
                    ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                    ON CONFLICT (location_id, sku_id) DO UPDATE SET
                        reasons = EXCLUDED.reasons,
                        sell_through = EXCLUDED.sell_through
                    """;
            jdbc.update(sql, ps -> {
                ps.setObject(1, event.locationId());
                ps.setObject(2, event.skuId());
                Array array =
                        ps.getConnection().createArrayOf("text", event.reasons().toArray(new String[0]));
                ps.setArray(3, array);
                ps.setBoolean(4, event.sellThrough());
                ps.setObject(5, event.ownerEntityId());
            });
        }
    }

    public void upsert(
            UUID locationId,
            UUID skuId,
            List<String> reasons,
            boolean sellThrough,
            UUID ownerEntityId,
            ScopeContext scope) {
        onAssortmentDrift(
                new AssortmentDriftDetected(
                        locationId, skuId, reasons, sellThrough, ownerEntityId, AssortmentDriftDetected.Action.UPSERT),
                scope);
    }

    public void delete(UUID locationId, UUID skuId, ScopeContext scope) {
        onAssortmentDrift(
                new AssortmentDriftDetected(
                        locationId, skuId, List.of(), false, null, AssortmentDriftDetected.Action.DELETE),
                scope);
    }

    // ---- 1. Price List Published -------------------------------------------------------------

    @EventConsumer(
            types = {"price_list.published.v1"},
            consumer = CONSUMER)
    @Transactional
    public void onPriceListPublished(JsonNode payload, ScopeContext scope) {
        String kind = str(payload, "kind");
        if (!"RETAIL".equalsIgnoreCase(kind)) {
            return;
        }

        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        if (ownerEntityId == null) {
            return;
        }

        List<UUID> skuIds = uuids(payload, "skuIds");
        List<Target> targets = shops.of(ownerEntityId, scope);
        if (targets.isEmpty()) {
            return;
        }

        for (Target target : targets) {
            UUID locationId = target.locationId();

            // 1. Add RETAIL_LIST reason for SKUs on the published price list
            for (UUID skuId : skuIds) {
                Optional<AssortmentRecord> existing = store.get(locationId, skuId);
                if (existing.isPresent()) {
                    List<String> reasons = new ArrayList<>(existing.get().reasons());
                    if (!reasons.contains(REASON_RETAIL_LIST)) {
                        reasons.add(REASON_RETAIL_LIST);
                        upsert(locationId, skuId, reasons, existing.get().sellThrough(), ownerEntityId, scope);
                    }
                } else {
                    upsert(locationId, skuId, List.of(REASON_RETAIL_LIST), false, ownerEntityId, scope);
                    changeLog.append(List.of(target), List.of(Change.upsert(SKU_TABLE, skuId)), null, false, scope);
                }
            }

            // 2. Remove RETAIL_LIST reason for SKUs no longer on the published price list
            List<AssortmentRecord> current = store.findByLocation(locationId);
            for (AssortmentRecord record : current) {
                if (record.reasons().contains(REASON_RETAIL_LIST) && !skuIds.contains(record.skuId())) {
                    List<String> reasons = new ArrayList<>(record.reasons());
                    reasons.remove(REASON_RETAIL_LIST);
                    if (reasons.isEmpty() && !record.sellThrough()) {
                        delete(locationId, record.skuId(), scope);
                        changeLog.append(
                                List.of(target), List.of(Change.delete(SKU_TABLE, record.skuId())), null, false, scope);
                    } else {
                        upsert(locationId, record.skuId(), reasons, record.sellThrough(), ownerEntityId, scope);
                    }
                }
            }
        }
    }

    // ---- 2. Stock Moved & Negative Lot --------------------------------------------------------

    @EventConsumer(
            types = {"stock.moved.v1", "lot.negative.v1"},
            consumer = CONSUMER)
    @Transactional
    public void onStockChanged(JsonNode payload, ScopeContext scope) {
        UUID locationId = uuid(payload, "locationId");
        UUID skuId = uuid(payload, "skuId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");

        if (locationId == null || skuId == null) {
            return;
        }

        if (ownerEntityId == null) {
            ownerEntityId = findLocationOwner(locationId);
        }

        boolean hasStock = inventory.hasPositiveLotAt(locationId, skuId, scope);
        String skuStatus = findSkuStatus(skuId);
        boolean isInactive = "INACTIVE".equalsIgnoreCase(skuStatus);

        Optional<AssortmentRecord> existing = store.get(locationId, skuId);

        if (hasStock) {
            if (existing.isPresent()) {
                List<String> reasons = new ArrayList<>(existing.get().reasons());
                if (!reasons.contains(REASON_LOT)) {
                    reasons.add(REASON_LOT);
                }
                boolean sellThrough = isInactive || existing.get().sellThrough();
                upsert(locationId, skuId, reasons, sellThrough, ownerEntityId, scope);
            } else {
                upsert(locationId, skuId, List.of(REASON_LOT), isInactive, ownerEntityId, scope);
                changeLog.append(
                        List.of(new Target(ownerEntityId, locationId)),
                        List.of(Change.upsert(SKU_TABLE, skuId)),
                        null,
                        false,
                        scope);
            }
        } else {
            // No positive on-hand lots remain
            if (existing.isPresent()) {
                if (isInactive) {
                    // Inactive SKU completely sold out: remove from assortment and tombstone to till
                    delete(locationId, skuId, scope);
                    changeLog.append(
                            List.of(new Target(ownerEntityId, locationId)),
                            List.of(Change.delete(SKU_TABLE, skuId)),
                            null,
                            false,
                            scope);
                } else {
                    List<String> reasons = new ArrayList<>(existing.get().reasons());
                    reasons.remove(REASON_LOT);
                    if (reasons.isEmpty() && !existing.get().sellThrough()) {
                        delete(locationId, skuId, scope);
                        changeLog.append(
                                List.of(new Target(ownerEntityId, locationId)),
                                List.of(Change.delete(SKU_TABLE, skuId)),
                                null,
                                false,
                                scope);
                    } else {
                        upsert(locationId, skuId, reasons, existing.get().sellThrough(), ownerEntityId, scope);
                    }
                }
            }
        }
    }

    // ---- 3. SKU Activated & Reactivated -------------------------------------------------------

    @EventConsumer(
            types = {SkuActivated.TYPE, SkuReactivated.TYPE},
            consumer = CONSUMER)
    @Transactional
    public void onSkuActivated(JsonNode payload, ScopeContext scope) {
        UUID skuId = uuid(payload, "skuId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        String status = str(payload, "status");

        if (skuId == null || ownerEntityId == null) {
            return;
        }

        if ("LOCAL".equalsIgnoreCase(status)) {
            List<Target> targets = shops.of(ownerEntityId, scope);
            for (Target target : targets) {
                Optional<AssortmentRecord> existing = store.get(target.locationId(), skuId);
                if (existing.isEmpty()) {
                    upsert(target.locationId(), skuId, List.of(REASON_LOCAL), false, ownerEntityId, scope);
                    changeLog.append(List.of(target), List.of(Change.upsert(SKU_TABLE, skuId)), null, false, scope);
                } else {
                    List<String> reasons = new ArrayList<>(existing.get().reasons());
                    if (!reasons.contains(REASON_LOCAL)) {
                        reasons.add(REASON_LOCAL);
                    }
                    upsert(target.locationId(), skuId, reasons, false, ownerEntityId, scope);
                }
            }
        }
    }

    // ---- 4. SKU Deactivated -------------------------------------------------------------------

    @EventConsumer(
            types = {SkuDeactivated.TYPE},
            consumer = CONSUMER)
    @Transactional
    public void onSkuDeactivated(JsonNode payload, ScopeContext scope) {
        UUID skuId = uuid(payload, "skuId");
        if (skuId == null) {
            return;
        }

        List<AssortmentRecord> records = store.findBySku(skuId);
        for (AssortmentRecord record : records) {
            boolean hasStock = inventory.hasPositiveLotAt(record.locationId(), skuId, scope);
            if (hasStock) {
                // Sell-through: keep selling lots on hand until sold out
                upsert(record.locationId(), skuId, record.reasons(), true, record.ownerEntityId(), scope);
            } else {
                // No stock: remove immediately from assortment and push tombstone
                delete(record.locationId(), skuId, scope);
                changeLog.append(
                        List.of(new Target(record.ownerEntityId(), record.locationId())),
                        List.of(Change.delete(SKU_TABLE, skuId)),
                        null,
                        true,
                        scope);
            }
        }
    }

    // ---- 5. SKU Updated (Governed Core-Range Tag) ---------------------------------------------

    @EventConsumer(
            types = {SkuUpdated.TYPE},
            consumer = CONSUMER)
    @Transactional
    public void onSkuUpdated(JsonNode payload, ScopeContext scope) {
        UUID skuId = uuid(payload, "skuId");
        if (skuId == null) {
            return;
        }

        boolean hasCoreRangeTag = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM catalogue.sku_tag st
                      JOIN catalogue.tag t ON t.tag_id = st.tag_id
                     WHERE st.sku_id = ? AND t.tag_code = 'core-range' AND t.governed
                )
                """,
                Boolean.class,
                skuId));

        if (hasCoreRangeTag) {
            List<Target> allShops = shops.all(scope);
            for (Target target : allShops) {
                Optional<AssortmentRecord> existing = store.get(target.locationId(), skuId);
                if (existing.isEmpty()) {
                    upsert(
                            target.locationId(),
                            skuId,
                            List.of(REASON_CORE_RANGE),
                            false,
                            target.ownerEntityId(),
                            scope);
                    changeLog.append(List.of(target), List.of(Change.upsert(SKU_TABLE, skuId)), null, false, scope);
                } else {
                    List<String> reasons = new ArrayList<>(existing.get().reasons());
                    if (!reasons.contains(REASON_CORE_RANGE)) {
                        reasons.add(REASON_CORE_RANGE);
                        upsert(
                                target.locationId(),
                                skuId,
                                reasons,
                                existing.get().sellThrough(),
                                target.ownerEntityId(),
                                scope);
                    }
                }
            }
        } else {
            List<AssortmentRecord> recordsWithCoreRange = store.findBySkuAndReason(skuId, REASON_CORE_RANGE);
            for (AssortmentRecord record : recordsWithCoreRange) {
                List<String> reasons = new ArrayList<>(record.reasons());
                reasons.remove(REASON_CORE_RANGE);
                if (reasons.isEmpty() && !record.sellThrough()) {
                    delete(record.locationId(), skuId, scope);
                    changeLog.append(
                            List.of(new Target(record.ownerEntityId(), record.locationId())),
                            List.of(Change.delete(SKU_TABLE, skuId)),
                            null,
                            false,
                            scope);
                } else {
                    upsert(record.locationId(), skuId, reasons, record.sellThrough(), record.ownerEntityId(), scope);
                }
            }
        }
    }

    // ---- 6. SKU Merged ------------------------------------------------------------------------

    @EventConsumer(
            types = {SkuMerged.TYPE},
            consumer = CONSUMER)
    @Transactional
    public void onSkuMerged(JsonNode payload, ScopeContext scope) {
        UUID sourceSkuId = uuid(payload, "sourceSkuId");
        if (sourceSkuId == null) {
            return;
        }

        // Source SKU was merged and is now INACTIVE. Handle sell-through or removal
        List<AssortmentRecord> records = store.findBySku(sourceSkuId);
        for (AssortmentRecord record : records) {
            boolean hasStock = inventory.hasPositiveLotAt(record.locationId(), sourceSkuId, scope);
            if (hasStock) {
                upsert(record.locationId(), sourceSkuId, record.reasons(), true, record.ownerEntityId(), scope);
            } else {
                delete(record.locationId(), sourceSkuId, scope);
                changeLog.append(
                        List.of(new Target(record.ownerEntityId(), record.locationId())),
                        List.of(Change.delete(SKU_TABLE, sourceSkuId)),
                        null,
                        true,
                        scope);
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private String findSkuStatus(UUID skuId) {
        List<String> list = jdbc.queryForList("SELECT status FROM catalogue.sku WHERE sku_id = ?", String.class, skuId);
        return list.isEmpty() ? null : list.getFirst();
    }

    private UUID findLocationOwner(UUID locationId) {
        List<UUID> list = jdbc.queryForList(
                "SELECT owner_entity_id FROM catalogue.location_assortment WHERE location_id = ? LIMIT 1",
                UUID.class,
                locationId);
        if (!list.isEmpty()) {
            return list.getFirst();
        }
        return jdbc
                .queryForList(
                        "SELECT owner_entity_id FROM party.location WHERE location_id = ?", UUID.class, locationId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static String str(JsonNode node, String field) {
        JsonNode val = node == null ? null : node.get(field);
        return val == null || val.isNull() ? null : val.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        String val = str(node, field);
        return val == null ? null : UUID.fromString(val);
    }

    private static List<UUID> uuids(JsonNode node, String field) {
        JsonNode val = node == null ? null : node.get(field);
        if (val == null || !val.isArray()) {
            return Collections.emptyList();
        }
        List<UUID> list = new ArrayList<>(val.size());
        for (JsonNode item : val) {
            if (item != null && !item.isNull()) {
                list.add(UUID.fromString(item.asText()));
            }
        }
        return Collections.unmodifiableList(list);
    }
}
