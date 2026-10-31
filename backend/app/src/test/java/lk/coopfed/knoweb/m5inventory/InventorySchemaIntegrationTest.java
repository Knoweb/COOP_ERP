package lk.coopfed.knoweb.m5inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M5-01's "done when" (25A section 10): the migration applies and the grants hold. The ledger is
 * insert-only for the application; a lot's identity and cost cannot be changed, only its ledger
 * columns; the movement partitions exist ahead of the calendar with row-level security forced.
 * Row-level security itself is proved cell by cell for every table by the kernel's
 * {@code RlsMatrixIntegrationTest}, which finds these tables by their owner column.
 */
class InventorySchemaIntegrationTest extends PostgresIntegrationTest {

    /** The application's own connection: coop_app, a member of app_rw, not a superuser. */
    @Autowired
    private JdbcTemplate appJdbc;

    @Test
    void theApplicationMayNeitherChangeNorRemoveAMovement() {
        assertRefused("update inventory.stock_movement set qty_delta = 1");
        assertRefused("delete from inventory.stock_movement");
        assertRefused("truncate inventory.stock_movement");
    }

    @Test
    void aLotKeepsItsIdentityAndItsCostAndIsNeverRemoved() {
        assertRefused("update inventory.stock_lot set unit_cost = 1");
        assertRefused("update inventory.stock_lot set batch_id = gen_random_uuid()");
        assertRefused("update inventory.stock_lot set owner_entity_id = gen_random_uuid()");
        assertRefused("delete from inventory.stock_lot");
        assertRefused("delete from inventory.entity_sku_cost");
        assertRefused("delete from inventory.movement_sequence");
        // The ledger's own columns are granted (row-level security then shows a scope-less
        // transaction no row, so nothing changes).
        assertThat(appJdbc.update("update inventory.stock_lot set qty_on_hand = qty_on_hand"))
                .isZero();
    }

    @Test
    void repricingProcedureEnforcesScopeUserAuditAndCostInvariants() {
        UUID randomId = java.util.UUID.randomUUID();

        // 1. Direct call without OWN scope is refused
        assertThatThrownBy(() -> appJdbc.execute(
                        "select inventory.apply_lot_reprice(gen_random_uuid(), gen_random_uuid(), 10.00)"))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(
                                ((DataAccessException) e).getMostSpecificCause().getMessage())
                        .contains("m5.scope.own_required"));

        // 2. Direct call without authenticated user is refused
        assertThatThrownBy(() -> appJdbc.execute(
                        "select set_config('app.scope_class', 'OWN', true), "
                                + "set_config('app.scope_entity_id', gen_random_uuid()::text, true), "
                                + "inventory.apply_lot_reprice(gen_random_uuid(), gen_random_uuid(), 10.00)"))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(
                                ((DataAccessException) e).getMostSpecificCause().getMessage())
                        .contains("m5.reprice.user_required"));

        // 3. Direct call with negative cost is refused
        assertThatThrownBy(() -> appJdbc.execute(
                        "select set_config('app.scope_class', 'OWN', true), "
                                + "set_config('app.scope_entity_id', gen_random_uuid()::text, true), "
                                + "set_config('app.user_id', gen_random_uuid()::text, true), "
                                + "inventory.apply_lot_reprice(gen_random_uuid(), gen_random_uuid(), -5.00)"))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(
                                ((DataAccessException) e).getMostSpecificCause().getMessage())
                        .contains("m5.reprice.invalid_cost"));

        // 4. Direct call without an immutable stock_repricing audit record in current transaction is refused
        assertThatThrownBy(() -> appJdbc.execute(
                        "select set_config('app.scope_class', 'OWN', true), "
                                + "set_config('app.scope_entity_id', gen_random_uuid()::text, true), "
                                + "set_config('app.user_id', gen_random_uuid()::text, true), "
                                + "inventory.apply_lot_reprice(gen_random_uuid(), gen_random_uuid(), 10.00)"))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(
                                ((DataAccessException) e).getMostSpecificCause().getMessage())
                        .contains("m5.reprice.audit_required"));
    }

    @Test
    void transferLifecycleFactsAreInsertOnlyAndForceRowLevelSecurity() {
        for (String table : List.of(
                "transfer_receipt_line", "transfer_cancellation", "transfer_shortfall", "transfer_shortfall_line")) {
            assertRefused("update inventory." + table + " set owner_entity_id = owner_entity_id");
            assertRefused("delete from inventory." + table);
            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select relrowsecurity and relforcerowsecurity"
                                            + " from pg_class where oid = ('inventory.' || ?)::regclass",
                                    Boolean.class,
                                    table))
                    .as("inventory.%s enables and forces RLS", table)
                    .isTrue();
        }
    }

    @Test
    void pickOverridesAreInsertOnlyForcedAndKeepBothLotIdentities() {
        assertRefused("update inventory.pick_override set reason = reason");
        assertRefused("delete from inventory.pick_override");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select relrowsecurity and relforcerowsecurity"
                                        + " from pg_class where oid = 'inventory.pick_override'::regclass",
                                Boolean.class))
                .isTrue();

        List<String> foreignKeys = superuserJdbc()
                .queryForList(
                        "select pg_get_constraintdef(oid) from pg_constraint"
                                + " where conrelid = 'inventory.pick_override'::regclass and contype = 'f'",
                        String.class);
        assertThat(foreignKeys)
                .anySatisfy(definition -> assertThat(definition)
                        .contains(
                                "(pick_line_id, pick_list_id, owner_entity_id, location_id, original_stock_lot_id, sku_id)",
                                "inventory.pick_list_line"))
                .anySatisfy(definition -> assertThat(definition)
                        .contains(
                                "(replacement_stock_lot_id, owner_entity_id, location_id, sku_id, replacement_batch_id, condition)",
                                "inventory.stock_lot"));
    }

    @Test
    void transferLifecycleFactsKeepTheirCompositeParentIdentity() {
        List<String> receiptLineForeignKeys = superuserJdbc()
                .queryForList(
                        "select pg_get_constraintdef(oid) from pg_constraint"
                                + " where conrelid = 'inventory.transfer_receipt_line'::regclass"
                                + " and contype = 'f' order by pg_get_constraintdef(oid)",
                        String.class);
        assertThat(receiptLineForeignKeys)
                .anySatisfy(
                        definition -> assertThat(definition)
                                .contains(
                                        "(transfer_id, owner_entity_id, location_id, from_location_id)",
                                        "inventory.transfer_receipt(transfer_id, owner_entity_id, location_id, from_location_id)"))
                .anySatisfy(
                        definition -> assertThat(definition)
                                .contains(
                                        "(transfer_id, transfer_line_id, owner_entity_id, from_location_id, location_id)",
                                        "inventory.transfer_line(transfer_id, line_id, owner_entity_id, location_id, to_location_id)"));

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select pg_get_constraintdef(oid) from pg_constraint"
                                        + " where conrelid = 'inventory.transfer_cancellation'::regclass"
                                        + " and contype = 'f'",
                                String.class))
                .contains(
                        "(transfer_id, owner_entity_id, location_id, to_location_id)",
                        "inventory.transfer(transfer_id, owner_entity_id, location_id, to_location_id)");

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select pg_get_constraintdef(oid) from pg_constraint"
                                        + " where conrelid = 'inventory.transfer_cancellation'::regclass"
                                        + " and conname = 'transfer_cancellation_return_verification'",
                                String.class))
                .contains(
                        "cancelled_from_status = 'ISSUED'",
                        "physical_return_verified_by IS NULL",
                        "cancelled_from_status = 'IN_TRANSIT'",
                        "physical_return_verified_at IS NOT NULL");

        List<String> shortfallLineForeignKeys = superuserJdbc()
                .queryForList(
                        "select pg_get_constraintdef(oid) from pg_constraint"
                                + " where conrelid = 'inventory.transfer_shortfall_line'::regclass"
                                + " and contype = 'f'",
                        String.class);
        assertThat(shortfallLineForeignKeys)
                .anySatisfy(
                        definition -> assertThat(definition)
                                .contains(
                                        "(shortfall_id, transfer_id, owner_entity_id, location_id, from_location_id)",
                                        "inventory.transfer_shortfall(shortfall_id, transfer_id, owner_entity_id, location_id, from_location_id)"))
                .anySatisfy(
                        definition -> assertThat(definition)
                                .contains(
                                        "(transfer_id, transfer_line_id, owner_entity_id, location_id, from_location_id)",
                                        "inventory.transfer_receipt_line(transfer_id, transfer_line_id, owner_entity_id, location_id, from_location_id)"));
    }

    @Test
    void theMovementPartitionsExistAheadWithRowLevelSecurityForced() {
        List<Map<String, Object>> partitions = superuserJdbc()
                .queryForList(
                        """
                select c.relname, c.relrowsecurity, c.relforcerowsecurity
                  from pg_inherits i join pg_class c on c.oid = i.inhrelid
                 where i.inhparent = 'inventory.stock_movement'::regclass
                """);
        assertThat(partitions).hasSizeGreaterThanOrEqualTo(5); // the default and four months
        assertThat(partitions).allSatisfy(p -> {
            assertThat(p.get("relrowsecurity")).isEqualTo(true);
            assertThat(p.get("relforcerowsecurity")).isEqualTo(true);
        });
        assertThat(superuserJdbc().queryForObject("select inventory.ensure_movement_partitions(3)", Integer.class))
                .as("a second run creates nothing")
                .isZero();
    }

    private void assertRefused(String sql) {
        assertThatThrownBy(() -> appJdbc.execute(sql))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(
                                ((DataAccessException) e).getMostSpecificCause().getMessage())
                        .contains("permission denied"));
    }
}
