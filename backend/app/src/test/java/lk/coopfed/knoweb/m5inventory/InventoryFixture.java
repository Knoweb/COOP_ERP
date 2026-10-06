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
    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> roles = new ArrayList<>();

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

    /**
     * An active local item of the owner that is neither batch- nor expiry-tracked and carries no
     * printed MRP (goods sold loose, or the packs an entity makes), of the given M2 origin.
     */
    public UUID plainSku(UUID owner, String code, String originKind) {
        UUID sku = Ids.next();
        admin.update(
                """
                insert into catalogue.sku
                    (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                     base_uom_code, tax_category_id, batch_tracked, expiry_tracked, has_printed_mrp, origin_kind)
                values (?, ?, ?, 'LOCAL', ?, ?, ?, 'EA', ?, false, false, false, ?)
                """,
                sku,
                "M5" + sku.toString().replace("-", "").substring(22),
                owner,
                code,
                code,
                code,
                TAX_CATEGORY,
                originKind);
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

    /** The entity's M1 row (its code names its document series); kept between tests, it is reference data here. */
    public void entity(UUID id, String code, String type) {
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en) values (?, ?, ?, ?)"
                        + " on conflict do nothing",
                id,
                code,
                type,
                "M5 test " + code);
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

    /**
     * A user of the entity holding, entity-wide, a role with the permission and its limits (null:
     * a grant without limits). Since wave 2 an approval limit fails closed (M5-09): an approver needs
     * a grant at the entity, and one without {@code max_value} approves band 1 only.
     */
    public UUID userWith(UUID entity, String permission, String limits) {
        return userWithAll(entity, limits, permission);
    }

    /** A user of the entity holding, entity-wide, one role with every permission given, each with the limits. */
    public UUID userWithAll(UUID entity, String limits, String... permissions) {
        UUID user = Ids.next();
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                        + " values (?, ?, ?, ?, 'BACK_OFFICE', 'ACTIVE')",
                user,
                entity,
                "m5-" + user,
                "M5 test user");
        users.add(user);
        UUID role = Ids.next();
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, ?, false, 'OWN', 'ACTIVE')",
                role,
                entity,
                "M5 test role " + role);
        roles.add(role);
        for (String permission : permissions) {
            admin.update(
                    "insert into security.role_permission (role_id, permission_code, limits) values (?, ?, ?::jsonb)",
                    role,
                    permission,
                    limits);
        }
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id)"
                        + " values (?, ?, ?, null)",
                user,
                role,
                entity);
        return user;
    }

    public void clean() {
        for (UUID user : users) {
            admin.update("delete from security.user_role where user_id = ?", user);
            admin.update("delete from security.app_user where user_id = ?", user);
        }
        for (UUID role : roles) {
            admin.update("delete from security.role_permission where role_id = ?", role);
            admin.update("delete from security.role where role_id = ?", role);
        }
        users.clear();
        roles.clear();
        admin.execute("truncate table inventory.stock_lot, inventory.stock_movement, inventory.movement_sequence,"
                + " inventory.entity_sku_cost, inventory.pick_list_line, inventory.pick_list,"
                + " inventory.opening_balance_line, inventory.opening_balance, inventory.transfer_receipt,"
                + " inventory.transfer_line, inventory.transfer, inventory.count_line, inventory.count_expectation,"
                + " inventory.count_task, inventory.write_off_photo, inventory.write_off_line, inventory.write_off,"
                + " inventory.repack_reversal, inventory.repack, inventory.repack_recipe");
        for (UUID batch : batches) {
            admin.update("delete from catalogue.batch_key where batch_id = ?", batch);
            admin.update("delete from catalogue.batch where batch_id = ?", batch);
        }
        for (UUID sku : skus) {
            // A test may have corrected a batch: its replacement is a batch of the same item.
            admin.update(
                    "delete from catalogue.batch_key where batch_id in (select batch_id from catalogue.batch where sku_id = ?)",
                    sku);
            admin.update("delete from catalogue.batch where sku_id = ?", sku);
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

    /** The scope an event consumer runs in: the event owner's OWN scope, no user (EventConsumerDispatcher). */
    public static ScopeContext system(UUID entity) {
        lk.coopfed.knoweb.kernel.api.Scope scope = new lk.coopfed.knoweb.kernel.api.Scope(entity, null);
        return new ScopeContext(
                null,
                null,
                entity,
                java.util.List.of(scope),
                scope,
                lk.coopfed.knoweb.kernel.api.PolicyClass.OWN,
                java.util.Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
    }

    /** An entity-wide OWN scope of another user of the entity (a second signature). */
    public static ScopeContext own(UUID entity, UUID user) {
        return ScopeContext.dev(user, entity, null);
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
