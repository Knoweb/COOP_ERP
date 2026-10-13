package lk.coopfed.knoweb.m2catalogue.internal.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeLinked;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRegistered;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRetired;
import lk.coopfed.knoweb.m2catalogue.api.ConversionDefined;
import lk.coopfed.knoweb.m2catalogue.api.ImageAttached;
import lk.coopfed.knoweb.m2catalogue.api.ImageRetired;
import lk.coopfed.knoweb.m2catalogue.api.SkuActivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuDeactivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuReactivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuShared;
import lk.coopfed.knoweb.m2catalogue.api.SkuUpdated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Change-log producer for M2 Catalogue & Batch (22A section 7.2; doc 32 section 5.2).
 *
 * <p>Listens to domain events for SKUs, barcodes, unit conversions and product images, and
 * records delta rows into {@code kernel.change_log} for the appropriate shops (every shop in any
 * status, see {@link CatalogueShops}):
 * <ul>
 *   <li>SHARED SKU mutations fan out to every shop across the federation.
 *   <li>LOCAL SKU mutations fan out strictly to the shops of the owning entity.
 *   <li>Local image overrides on a SHARED SKU fan out strictly to the overriding entity's shops.
 *   <li>SKU deactivations are flagged urgent so enrolled tills pick them up on the next heartbeat.
 * </ul>
 *
 * <p>Batches are not recorded here: doc 32 section 5.1 puts the batches a till holds in the Lots
 * table set ("batches with positive on-hand at the location", from M5 with the batch identity
 * from M2), and the till reads no batch rows yet. A batch registration or correction reaches a
 * till through M5's lot contributor once it exists (wave 4 TD-02 and TD-04).
 */
@Component
public class M2ChangeLogFanOut {

    private static final Logger log = LoggerFactory.getLogger(M2ChangeLogFanOut.class);

    static final String CONSUMER = "m2.snapshot";
    static final String SKU = CatalogueSnapshotContributor.SKU;
    static final String STATUS_SHARED = "SHARED";

    private final ChangeLog changeLog;
    private final CatalogueShops shops;
    private final JdbcTemplate jdbc;
    private final UUID federationEntityId;

    public M2ChangeLogFanOut(
            ChangeLog changeLog,
            CatalogueShops shops,
            JdbcTemplate jdbc,
            @Value("${coop-erp.system.entity-id:}") String federationEntityId) {
        this.changeLog = changeLog;
        this.shops = shops;
        this.jdbc = jdbc;
        this.federationEntityId = federationEntityId == null || federationEntityId.isBlank()
                ? null
                : UUID.fromString(federationEntityId.strip());
    }

    // ---- SKU lifecycle ------------------------------------------------------------------------

    @EventConsumer(
            types = {SkuActivated.TYPE, SkuShared.TYPE, SkuUpdated.TYPE, SkuReactivated.TYPE},
            consumer = CONSUMER)
    public void onSkuChanged(JsonNode payload, ScopeContext scope) {
        UUID skuId = uuid(payload, "skuId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        String status = str(payload, "status");
        if (skuId == null) {
            throw new IllegalArgumentException("SKU event payload is missing skuId");
        }

        boolean shared = STATUS_SHARED.equalsIgnoreCase(status) || isFederation(ownerEntityId);
        List<Target> targets = shared ? shops.all(scope) : shops.of(ownerEntityId, scope);
        if (!targets.isEmpty()) {
            changeLog.append(targets, List.of(Change.upsert(SKU, skuId)), null, false, scope);
        }
    }

    @EventConsumer(
            types = {SkuDeactivated.TYPE},
            consumer = CONSUMER)
    public void onSkuDeactivated(JsonNode payload, ScopeContext scope) {
        UUID skuId = uuid(payload, "skuId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        String priorStatus = str(payload, "priorStatus");
        if (skuId == null) {
            throw new IllegalArgumentException("SkuDeactivated event payload is missing skuId");
        }

        boolean wasShared = STATUS_SHARED.equalsIgnoreCase(priorStatus) || isFederation(ownerEntityId);
        List<Target> targets = wasShared ? shops.all(scope) : shops.of(ownerEntityId, scope);
        if (!targets.isEmpty()) {
            changeLog.append(targets, List.of(Change.upsert(SKU, skuId)), null, true, scope);
        }
    }

    // ---- Barcodes -----------------------------------------------------------------------------

    @EventConsumer(
            types = {BarcodeRegistered.TYPE, BarcodeRetired.TYPE, BarcodeLinked.TYPE},
            consumer = CONSUMER)
    public void onBarcodeChanged(JsonNode payload, ScopeContext scope) {
        UUID skuId = uuid(payload, "skuId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        if (skuId == null) {
            throw new IllegalArgumentException("Barcode event payload is missing skuId");
        }

        SkuMeta meta = skuMeta(skuId);
        boolean shared = meta != null ? STATUS_SHARED.equalsIgnoreCase(meta.status()) : isFederation(ownerEntityId);
        UUID targetOwner = meta != null && meta.ownerEntityId() != null ? meta.ownerEntityId() : ownerEntityId;

        List<Target> targets = shared ? shops.all(scope) : shops.of(targetOwner, scope);
        if (!targets.isEmpty()) {
            changeLog.append(targets, List.of(Change.upsert(SKU, skuId)), null, false, scope);
        }
    }

    // ---- Unit conversions ---------------------------------------------------------------------

    @EventConsumer(
            types = {ConversionDefined.TYPE},
            consumer = CONSUMER)
    public void onConversionChanged(JsonNode payload, ScopeContext scope) {
        UUID skuId = uuid(payload, "skuId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        if (skuId == null) {
            throw new IllegalArgumentException("ConversionDefined event payload is missing skuId");
        }

        SkuMeta meta = skuMeta(skuId);
        boolean shared = meta != null ? STATUS_SHARED.equalsIgnoreCase(meta.status()) : isFederation(ownerEntityId);
        UUID targetOwner = meta != null && meta.ownerEntityId() != null ? meta.ownerEntityId() : ownerEntityId;

        List<Target> targets = shared ? shops.all(scope) : shops.of(targetOwner, scope);
        if (!targets.isEmpty()) {
            changeLog.append(targets, List.of(Change.upsert(SKU, skuId)), null, false, scope);
        }
    }

    // ---- Images -------------------------------------------------------------------------------

    @EventConsumer(
            types = {ImageAttached.TYPE, ImageRetired.TYPE},
            consumer = CONSUMER)
    public void onImageChanged(JsonNode payload, ScopeContext scope) {
        UUID skuId = uuid(payload, "skuId");
        UUID imageOwnerId = uuid(payload, "ownerEntityId");
        if (skuId == null) {
            throw new IllegalArgumentException("Image event payload is missing skuId");
        }

        SkuMeta meta = skuMeta(skuId);
        List<Target> targets;
        if (meta != null && STATUS_SHARED.equalsIgnoreCase(meta.status())) {
            if (imageOwnerId != null
                    && !isFederation(imageOwnerId)
                    && !Objects.equals(imageOwnerId, meta.ownerEntityId())) {
                // Local override on a SHARED SKU by an MPCS entity: reaches that entity's shops only.
                targets = shops.of(imageOwnerId, scope);
            } else {
                targets = shops.all(scope);
            }
        } else {
            UUID targetOwner = meta != null && meta.ownerEntityId() != null ? meta.ownerEntityId() : imageOwnerId;
            targets = shops.of(targetOwner, scope);
        }

        if (!targets.isEmpty()) {
            changeLog.append(targets, List.of(Change.upsert(SKU, skuId)), null, false, scope);
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    record SkuMeta(String status, UUID ownerEntityId) {}

    private SkuMeta skuMeta(UUID skuId) {
        List<SkuMeta> rows = jdbc.query(
                "select status, owner_entity_id from catalogue.sku where sku_id = ?",
                (rs, i) -> new SkuMeta(rs.getString("status"), rs.getObject("owner_entity_id", UUID.class)),
                skuId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean isFederation(UUID entityId) {
        return federationEntityId != null && federationEntityId.equals(entityId);
    }

    private static UUID uuid(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : UUID.fromString(node.asText());
    }

    private static String str(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
