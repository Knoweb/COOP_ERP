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
