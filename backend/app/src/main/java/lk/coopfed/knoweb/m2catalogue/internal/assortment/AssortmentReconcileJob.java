package lk.coopfed.knoweb.m2catalogue.internal.assortment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.api.RetailPriceListAssortmentQuery;
import lk.coopfed.knoweb.m2catalogue.internal.snapshot.CatalogueShops;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly assortment maintainer reconcile job (22A section 7.2; doc 22 section 3.8).
 *
 * <p>Recomputes the expected assortment for each shop from the 4 authoritative sources:
 * <ol>
 *   <li>The published RETAIL price lists of the owning entity in force (M3)
 *   <li>Positive on-hand lots at the location (M5)
 *   <li>Active LOCAL SKUs of the owning entity (M2)
 *   <li>Governed core-range tags (M2)
 * </ol>
 * Repairs drift in {@code catalogue.location_assortment}, emits {@code ASSORTMENT_DRIFT} audit
 * review events on any difference, and writes deltas to {@link ChangeLog}.
 */
@Component
public class AssortmentReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(AssortmentReconcileJob.class);

    public static final String AUDIT_ASSORTMENT_DRIFT = "ASSORTMENT_DRIFT";
    public static final String SKU_TABLE = "sku";

    private final AssortmentStore store;
    private final AssortmentMaintainer maintainer;
    private final CatalogueShops shops;
    private final ChangeLog changeLog;
    private final AuditFacade audit;
    private final InventoryLotQuery inventory;
    private final RetailPriceListAssortmentQuery pricing;
    private final JdbcTemplate jdbc;

    public AssortmentReconcileJob(
            AssortmentStore store,
            AssortmentMaintainer maintainer,
            CatalogueShops shops,
            ChangeLog changeLog,
            AuditFacade audit,
            InventoryLotQuery inventory,
            RetailPriceListAssortmentQuery pricing,
            JdbcTemplate jdbc) {
        this.store = store;
        this.maintainer = maintainer;
        this.shops = shops;
        this.changeLog = changeLog;
        this.audit = audit;
        this.inventory = inventory;
        this.pricing = pricing;
        this.jdbc = jdbc;
    }

    @ScheduledJob(
            name = "catalogue-assortment-reconcile",
            cron = "${coop-erp.m2.assortment-reconcile-cron:0 0 3 * * *}",
            lockTimeout = "PT30M",
            maxRuntime = "PT15M")
    public int reconcileAll(JobExecution execution) {
        ScopeContext view = execution.federationView();
        List<Target> allShops = shops.all(view);

        int totalDrifts = 0;
        for (Target target : allShops) {
            try {
                ScopeContext ownScope = execution.ownScopeOf(target.ownerEntityId());
                int drifts = reconcileLocation(target.locationId(), target.ownerEntityId(), ownScope);
                totalDrifts += drifts;
            } catch (RuntimeException e) {
                log.error("Failed to reconcile assortment for location {}", target.locationId(), e);
            }
        }

        execution.itemsProcessed(allShops.size());
        return totalDrifts;
    }

    /**
     * Reconciles a single location. Can be called directly by tests or administrative commands.
     */
    @Transactional
    public int reconcileLocation(UUID locationId, UUID ownerEntityId, ScopeContext scope) {
        Map<UUID, Set<String>> expected = computeExpectedAssortment(locationId, ownerEntityId, scope);
        List<AssortmentRecord> current = store.findByLocation(locationId);
        Map<UUID, AssortmentRecord> currentMap =
                current.stream().collect(Collectors.toMap(AssortmentRecord::skuId, Function.identity()));

        int drifts = 0;

        // 1. Process expected items
        for (Map.Entry<UUID, Set<String>> entry : expected.entrySet()) {
            UUID skuId = entry.getKey();
            List<String> expectedReasons = new ArrayList<>(entry.getValue());
            Collections.sort(expectedReasons);
            AssortmentRecord currentRecord = currentMap.get(skuId);

            if (currentRecord == null) {
                // Missing SKU from assortment
                maintainer.upsert(locationId, skuId, expectedReasons, false, ownerEntityId, scope);
                drifts++;
                recordDrift(locationId, skuId, "MISSING_SKU", null, expectedReasons, scope);
                changeLog.append(
                        List.of(new Target(ownerEntityId, locationId)),
                        List.of(Change.upsert(SKU_TABLE, skuId)),
                        null,
                        false,
                        scope);
            } else {
                List<String> currentReasons = new ArrayList<>(currentRecord.reasons());
                Collections.sort(currentReasons);
                if (!currentReasons.equals(expectedReasons) || currentRecord.sellThrough()) {
                    maintainer.upsert(locationId, skuId, expectedReasons, false, ownerEntityId, scope);
                    drifts++;
                    recordDrift(locationId, skuId, "REASONS_MISMATCH", currentReasons, expectedReasons, scope);
                }
            }
        }

        // 2. Process current items not in expected
        for (AssortmentRecord currentRecord : current) {
            UUID skuId = currentRecord.skuId();
            if (!expected.containsKey(skuId)) {
                String skuStatus = findSkuStatus(skuId);
                boolean isInactive = "INACTIVE".equalsIgnoreCase(skuStatus);
                boolean hasStock = inventory.hasPositiveLotAt(locationId, skuId, scope);

                if (isInactive && hasStock) {
                    // Sell-through candidate
                    if (!currentRecord.sellThrough()
                            || !currentRecord.reasons().contains(AssortmentMaintainer.REASON_LOT)) {
                        List<String> reasons = List.of(AssortmentMaintainer.REASON_LOT);
                        maintainer.upsert(locationId, skuId, reasons, true, ownerEntityId, scope);
                        drifts++;
                        recordDrift(locationId, skuId, "SELL_THROUGH_REPAIR", currentRecord.reasons(), reasons, scope);
                    }
                } else {
                    // Extraneous SKU
                    maintainer.delete(locationId, skuId, scope);
                    drifts++;
                    recordDrift(locationId, skuId, "EXTRANEOUS_SKU", currentRecord.reasons(), null, scope);
                    changeLog.append(
                            List.of(new Target(ownerEntityId, locationId)),
                            List.of(Change.delete(SKU_TABLE, skuId)),
                            null,
                            false,
                            scope);
                }
            }
        }

        return drifts;
    }

    private Map<UUID, Set<String>> computeExpectedAssortment(UUID locationId, UUID ownerEntityId, ScopeContext scope) {
        Map<UUID, Set<String>> expected = new HashMap<>();

        // 1. RETAIL_LIST from M3 published retail price lists
        try {
            List<UUID> retailSkus = pricing.skusOnRetailList(ownerEntityId, scope);
            for (UUID skuId : retailSkus) {
                expected.computeIfAbsent(skuId, k -> new HashSet<>()).add(AssortmentMaintainer.REASON_RETAIL_LIST);
            }
        } catch (Exception e) {
            log.warn("Could not query retail price lists for assortment reconcile: {}", e.getMessage());
        }

        // 2. LOT from M5 stock on-hand
        try {
            List<UUID> skusWithLots = inventory.skusWithLots(locationId, scope);
            for (UUID skuId : skusWithLots) {
                expected.computeIfAbsent(skuId, k -> new HashSet<>()).add(AssortmentMaintainer.REASON_LOT);
            }
        } catch (Exception e) {
            log.warn("Could not query lot stock for assortment reconcile: {}", e.getMessage());
        }

        // 3. LOCAL active SKUs of the owner entity
        List<UUID> localSkus = jdbc.queryForList(
                "SELECT sku_id FROM catalogue.sku WHERE owner_entity_id = ? AND status = 'LOCAL'",
                UUID.class,
                ownerEntityId);
        for (UUID skuId : localSkus) {
            expected.computeIfAbsent(skuId, k -> new HashSet<>()).add(AssortmentMaintainer.REASON_LOCAL);
        }

        // 4. CORE_RANGE governed tags
        List<UUID> coreRangeSkus = jdbc.queryForList(
                """
                SELECT st.sku_id
                  FROM catalogue.sku_tag st
                  JOIN catalogue.tag t ON t.tag_id = st.tag_id
                  JOIN catalogue.sku s ON s.sku_id = st.sku_id
                 WHERE t.tag_code = 'core-range' AND t.governed AND s.status IN ('LOCAL', 'SHARED')
                """,
                UUID.class);
        for (UUID skuId : coreRangeSkus) {
            expected.computeIfAbsent(skuId, k -> new HashSet<>()).add(AssortmentMaintainer.REASON_CORE_RANGE);
        }

        return expected;
    }

    private void recordDrift(
            UUID locationId,
            UUID skuId,
            String driftType,
            List<String> beforeReasons,
            List<String> afterReasons,
            ScopeContext scope) {
        Map<String, Object> before = beforeReasons != null
                ? Map.of("driftType", driftType, "skuId", skuId.toString(), "reasons", beforeReasons)
                : null;
        Map<String, Object> after = afterReasons != null
                ? Map.of("driftType", driftType, "skuId", skuId.toString(), "reasons", afterReasons)
                : Map.of("driftType", driftType, "skuId", skuId.toString());

        audit.record(AUDIT_ASSORTMENT_DRIFT, Subject.of("location", locationId), before, after, scope);
    }

    private String findSkuStatus(UUID skuId) {
        List<String> list = jdbc.queryForList("SELECT status FROM catalogue.sku WHERE sku_id = ?", String.class, skuId);
        return list.isEmpty() ? null : list.getFirst();
    }
}
