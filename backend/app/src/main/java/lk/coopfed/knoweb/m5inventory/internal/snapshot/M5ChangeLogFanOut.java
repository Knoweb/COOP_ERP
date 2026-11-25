package lk.coopfed.knoweb.m5inventory.internal.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.ConfigChanged;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.AdjustmentApproved;
import lk.coopfed.knoweb.m5inventory.api.AdjustmentRejected;
import lk.coopfed.knoweb.m5inventory.api.CountScheduled;
import lk.coopfed.knoweb.m5inventory.api.CountStarted;
import lk.coopfed.knoweb.m5inventory.api.CountSubmitted;
import lk.coopfed.knoweb.m5inventory.api.StockMoved;
import lk.coopfed.knoweb.m5inventory.api.TransferCancelled;
import lk.coopfed.knoweb.m5inventory.api.TransferDispatched;
import lk.coopfed.knoweb.m5inventory.api.TransferReceived;
import org.springframework.stereotype.Component;

/**
 * Change-log producer for M5 Inventory (doc 25A section 7.3, doc 32 section 5.2).
 *
 * <p>Listens to domain events for lots, count tasks, transfers, and inventory policies,
 * appending delta entries to {@code kernel.change_log} for the appropriate shops:
 * <ul>
 *   <li>Stock movement appends UPSERT on {@code lot}; a lot depleted to zero appends DELETE.
 *   <li>Count scheduled and started append UPSERT on {@code count_task}.
 *   <li>Count submitted, approved, or rejected appends DELETE on {@code count_task}.
 *   <li>Transfer dispatched appends UPSERT on {@code expected_transfer} for the destination shop.
 *   <li>Transfer received or cancelled appends DELETE on {@code expected_transfer} for the destination shop.
 *   <li>Inventory configuration changes append UPSERT on {@code inventory_policy}.
 * </ul>
 *
 * <p>Non-shop locations (warehouses, offices) are safely ignored: stock movements and counts
 * occurring at a warehouse do not fail and do not produce till snapshot deltas.
 */
@Component
public class M5ChangeLogFanOut {

    static final String CONSUMER = "m5.snapshot";

    static final Set<String> INVENTORY_POLICY_KEYS = Set.of(
            PolicySnapshotContributor.COUNT_TOLERANCE_PCT,
            PolicySnapshotContributor.COUNT_TOLERANCE_QTY,
            PolicySnapshotContributor.COUNT_TOLERANCE_VALUE,
            PolicySnapshotContributor.COUNT_AUTOPOST_VALUE_CAP,
            PolicySnapshotContributor.APPROVAL_BAND1_LIMIT,
            PolicySnapshotContributor.APPROVAL_BAND2_LIMIT,
            PolicySnapshotContributor.SINGLE_STAFF,
            PolicySnapshotContributor.REMOTE_WITNESS_ALLOWED,
            PolicySnapshotContributor.WRITEOFF_PHOTO_CATEGORIES,
            PolicySnapshotContributor.REPACK_YIELD_TOLERANCE_PCT,
            PolicySnapshotContributor.DISPATCH_MIN_SHELF_LIFE_DAYS);

    private final ChangeLog changeLog;
    private final M5Shops shops;

    public M5ChangeLogFanOut(ChangeLog changeLog, M5Shops shops) {
        this.changeLog = changeLog;
        this.shops = shops;
    }

    // ---- Lots ---------------------------------------------------------------------------------

    @EventConsumer(
            types = {StockMoved.TYPE},
            consumer = CONSUMER)
    public void onStockMoved(JsonNode payload, ScopeContext scope) {
        UUID locationId = uuid(payload, "locationId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        UUID stockLotId = uuid(payload, "stockLotId");
        if (locationId == null || ownerEntityId == null || stockLotId == null) {
            return;
        }

        if (!shops.isShop(ownerEntityId, locationId, scope)) {
            return;
        }

        JsonNode qtyNode = payload.get("lotQtyOnHand");
        BigDecimal qty = qtyNode != null && !qtyNode.isNull() ? new BigDecimal(qtyNode.asText()) : BigDecimal.ZERO;

        Change change = qty.compareTo(BigDecimal.ZERO) == 0
                ? Change.delete(LotSnapshotContributor.TABLE, stockLotId)
                : Change.upsert(LotSnapshotContributor.TABLE, stockLotId);

        changeLog.append(List.of(new Target(ownerEntityId, locationId)), List.of(change), null, false, scope);
    }

    // ---- Count Tasks --------------------------------------------------------------------------

    @EventConsumer(
            types = {CountScheduled.TYPE, CountStarted.TYPE},
            consumer = CONSUMER)
    public void onCountLifecycle(JsonNode payload, ScopeContext scope) {
        UUID locationId = uuid(payload, "locationId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        UUID taskId = uuid(payload, "taskId");
        if (locationId == null || ownerEntityId == null || taskId == null) {
            return;
        }

        if (!shops.isShop(ownerEntityId, locationId, scope)) {
            return;
        }

        changeLog.append(
                List.of(new Target(ownerEntityId, locationId)),
                List.of(Change.upsert(CountTaskSnapshotContributor.TABLE, taskId)),
                null,
                false,
                scope);
    }

    @EventConsumer(
            types = {CountSubmitted.TYPE, AdjustmentApproved.TYPE, AdjustmentRejected.TYPE},
            consumer = CONSUMER)
    public void onCountCompleted(JsonNode payload, ScopeContext scope) {
        UUID locationId = uuid(payload, "locationId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        UUID taskId = uuid(payload, "taskId");
        if (locationId == null || ownerEntityId == null || taskId == null) {
            return;
        }

        if (!shops.isShop(ownerEntityId, locationId, scope)) {
            return;
        }

        changeLog.append(
                List.of(new Target(ownerEntityId, locationId)),
                List.of(Change.delete(CountTaskSnapshotContributor.TABLE, taskId)),
                null,
                false,
                scope);
    }

    // ---- Transfers ----------------------------------------------------------------------------

    @EventConsumer(
            types = {TransferDispatched.TYPE},
            consumer = CONSUMER)
    public void onTransferDispatched(JsonNode payload, ScopeContext scope) {
        UUID toLocationId = uuid(payload, "toLocationId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        UUID transferId = uuid(payload, "transferId");
        if (toLocationId == null || ownerEntityId == null || transferId == null) {
            return;
        }

        if (!shops.isShop(ownerEntityId, toLocationId, scope)) {
            return;
        }

        changeLog.append(
                List.of(new Target(ownerEntityId, toLocationId)),
                List.of(Change.upsert(ExpectedTransferContributor.TABLE, transferId)),
                null,
                false,
                scope);
    }

    @EventConsumer(
            types = {TransferReceived.TYPE},
            consumer = CONSUMER)
    public void onTransferReceived(JsonNode payload, ScopeContext scope) {
        deleteExpectedTransfer(payload, scope);
    }

    @EventConsumer(
            types = {TransferCancelled.TYPE},
            consumer = CONSUMER)
    public void onTransferCancelled(JsonNode payload, ScopeContext scope) {
        deleteExpectedTransfer(payload, scope);
    }

    private void deleteExpectedTransfer(JsonNode payload, ScopeContext scope) {
        UUID toLocationId = uuid(payload, "toLocationId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        UUID transferId = uuid(payload, "transferId");
        if (toLocationId == null || ownerEntityId == null || transferId == null) {
            return;
        }

        if (!shops.isShop(ownerEntityId, toLocationId, scope)) {
            return;
        }

        changeLog.append(
                List.of(new Target(ownerEntityId, toLocationId)),
                List.of(Change.delete(ExpectedTransferContributor.TABLE, transferId)),
                null,
                false,
                scope);
    }

    // ---- Inventory Policy ---------------------------------------------------------------------

    @EventConsumer(
            types = {ConfigChanged.TYPE},
            consumer = CONSUMER)
    public void onConfigChanged(JsonNode payload, ScopeContext scope) {
        String key = str(payload, "key");
        if (key == null || !INVENTORY_POLICY_KEYS.contains(key)) {
            return;
        }

        UUID scopeLocation = uuid(payload, "scopeLocation");
        UUID scopeEntityId = uuid(payload, "scopeEntityId");

        List<Target> targets;
        if (scopeLocation != null) {
            UUID entityId = scopeEntityId != null ? scopeEntityId : scope.entityId();
            if (entityId != null && shops.isShop(entityId, scopeLocation, scope)) {
                targets = List.of(new Target(entityId, scopeLocation));
            } else {
                targets = List.of();
            }
        } else if (scopeEntityId != null) {
            targets = shops.of(scopeEntityId, scope);
        } else {
            targets = shops.all(scope);
        }

        for (Target target : targets) {
            changeLog.append(
                    List.of(target),
                    List.of(Change.upsert(PolicySnapshotContributor.TABLE, target.locationId())),
                    null,
                    false,
                    scope);
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static UUID uuid(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return UUID.fromString(node.asText());
    }

    private static String str(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return (node == null || node.isNull()) ? null : node.asText();
    }
}
