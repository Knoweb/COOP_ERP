package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The demo server's situation (wave 2 fix plan, "a Flyway test start on a database at the
 * previous migration"): a database whose kernel stands at V0083, holding a quarantined event,
 * migrates to V0084 with Flyway strict (out of order false, as application.yml's default), and the
 * row is still there, unresolved, with its raw event.
 */
class KernelMigrationFromV0083IntegrationTest extends PostgresIntegrationTest {

    private static final String DATABASE = "zz_kernel_from_v0083";

    @AfterEach
    void dropTheDatabase() {
        superuserJdbc().execute("drop database if exists " + DATABASE + " with (force)");
    }

    @Test
    void aDatabaseAtV0083MigratesToV0084StrictlyAndKeepsItsQuarantine() {
        superuserJdbc().execute("drop database if exists " + DATABASE + " with (force)");
        superuserJdbc().execute("create database " + DATABASE + " owner coop_migrator");
        String url = POSTGRES.getJdbcUrl().replace("/coop_erp", "/" + DATABASE);

        Flyway atV0083 =
                kernelFlyway(url).target(MigrationVersion.fromVersion("83")).load();
        atV0083.migrate();
        assertThat(atV0083.info().current().getVersion().compareTo(MigrationVersion.fromVersion("83")))
                .isZero();

        JdbcTemplate admin =
                new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID quarantineId = UUID.randomUUID();
        admin.update(
                """
                insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, location_id, batch_id,
                                                    device_seq, event_id, event_type, reason, detail, raw_event)
                values (?, ?, ?, ?, ?, 7, null, 'receipt.issued.v1', 'SCHEMA', 'line_no 1 is on two lines', '{"raw":1}')
                """,
                quarantineId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());

        Flyway latest = kernelFlyway(url).load();
        latest.migrate();

        assertThat(latest.info().current().getVersion().compareTo(MigrationVersion.fromVersion("84")))
                .isGreaterThanOrEqualTo(0);
        Map<String, Object> row =
                admin.queryForMap("select * from kernel.sync_quarantine where quarantine_id = ?", quarantineId);
        assertThat(row)
                .containsEntry("raw_event", "{\"raw\":1}")
                .containsEntry("reason", "SCHEMA")
                .containsEntry("resolution", null)
                .containsEntry("resolved_at", null);
    }

    /** The kernel's stream as FlywayConfig runs it, strict. */
    private static org.flywaydb.core.api.configuration.FluentConfiguration kernelFlyway(String url) {
        return Flyway.configure()
                .dataSource(url, "coop_migrator", "coop_migrator")
                .locations("classpath:db/migration/kernel")
                .schemas("kernel")
                .defaultSchema("kernel")
                .outOfOrder(false)
                .validateOnMigrate(true);
    }
}
