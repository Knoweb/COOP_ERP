package lk.coopfed.knoweb.m5inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The rows M5's tests stand on, written as the superuser into the schemas of the modules that own
 * them (M1's locations, M2's items and batches), and removed again by id. M5's own tables are
 * emptied whole: no other module writes them.
 */
public final class InventoryFixture {

    public static final UUID USER = UUID.fromString("0190e675-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e675-0000-7000-8000-000000000100");

    private final JdbcTemplate admin;
    private final List<UUID> skus = new ArrayList<>();
    private final List<UUID> batches = new ArrayList<>();
    private final List<UUID> locations = new ArrayList<>();

    public InventoryFixture(JdbcTemplate admin) {
        this.admin = admin;
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'EA', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M5TEST', 'M5 test tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                UUID.fromString("0190e000-0000-7000-8000-00000000f0f0"));
    }

    /** An active local item of the owner, batch- and expiry-tracked. */
    public UUID sku(UUID owner, String code) {
        UUID sku = Ids.next();
        admin.update(
                """
                insert into catalogue.sku
                    (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                     base_uom_code, tax_category_id, batch_tracked, expiry_tracked, has_printed_mrp)
                values (?, ?, ?, 'LOCAL', ?, ?, ?, 'EA', ?, true, true, true)
                """,
                sku,
                "M5" + sku.toString().replace("-", "").substring(22),
                owner,
                code,
                code,
                code,
                TAX_CATEGORY);
        skus.add(sku);
        return sku;
    }

    public UUID batch(UUID sku, UUID owner, String batchNo, LocalDate expiry) {
        UUID batch = Ids.next();
        admin.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, expiry_date, printed_mrp, owner_entity_id)"
                        + " values (?, ?, ?, ?, ?, ?)",
                batch,
                sku,
                batchNo + "-" + batch.toString().substring(24),
                expiry,
                new BigDecimal("500.00"),
                owner);
        batches.add(batch);
        return batch;
    }

    public UUID location(UUID owner, String type) {
        UUID id = Ids.next();
        admin.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en)"
                        + " values (?, ?, ?, ?, ?)",
                id,
                owner,
                "L" + id.toString().replace("-", "").substring(22).toUpperCase(),
                type,
                "M5 test " + type);
        locations.add(id);
        return id;
    }

    public void clean() {
        admin.execute("truncate table inventory.stock_lot, inventory.stock_movement, inventory.movement_sequence,"
                + " inventory.entity_sku_cost");
        for (UUID batch : batches) {
            admin.update("delete from catalogue.batch_key where batch_id = ?", batch);
            admin.update("delete from catalogue.batch where batch_id = ?", batch);
        }
        for (UUID sku : skus) {
            admin.update("delete from catalogue.sku where sku_id = ?", sku);
        }
        for (UUID location : locations) {
            admin.update("delete from kernel.location_business_date where location_id = ?", location);
            admin.update("delete from party.location where location_id = ?", location);
        }
        batches.clear();
        skus.clear();
        locations.clear();
    }

    /** An entity-wide OWN scope of a user. */
    public static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    /** An OWN scope at one location of the entity (a shop session). */
    public static ScopeContext at(UUID entity, UUID location) {
        return ScopeContext.dev(USER, entity, location);
    }
}
