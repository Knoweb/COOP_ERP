package lk.coopfed.knoweb.kernel.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Kernel V0085 on a database that already holds notifications, the demo server's situation: a
 * database migrated to the number below, rows with the plain SHA-256 recipient hash, then the
 * new migration. Every legacy hash becomes random hex (the clear recipient exists nowhere, so it
 * cannot be re-keyed, and an unkeyed hash of a phone number must not stay at rest), the rows
 * stay unique, and the replaced rows say so by a null key id (wave 2, TWK-20 and M9-07).
 *
 * <p>A database of its own in the shared container, migrated by Flyway as the migrator, as the
 * application does (FlywayConfig): the kernel's stream runs first and needs no other module.
 */
class NotificationLogLegacyHashMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final String DRILL = "kernel_v0085_drill";

    /** Stand-ins for the plain SHA-256 hashes the kernel wrote before V0085: 64 hex characters, obviously made up. */
    private static final String LEGACY_ONE = "ab".repeat(32);

    private static final String LEGACY_TWO = "cd".repeat(32);

    @AfterEach
    void dropTheDrill() {
        superuserJdbc().execute("drop database if exists " + DRILL + " with (force)");
    }

    @Test
    void theLegacyHashesAreReplacedByRandomValuesAndStayUnique() {
        superuserJdbc().execute("drop database if exists " + DRILL + " with (force)");
        superuserJdbc().execute("create database " + DRILL + " owner coop_migrator");
        String url = POSTGRES.getJdbcUrl().replace("/coop_erp", "/" + DRILL);

        // The database as the demo server holds it today: every kernel migration below V0085.
        // Flyway's target must name an existing migration: V0083 is the last one every branch has
        // (V0084 is the sync gateway's, built beside this one); the second run applies the rest.
        kernel(url).target("0083").load().migrate();

        JdbcTemplate admin =
                new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID rule = UUID.fromString("0190c100-0000-7000-8000-0000000000a1");
        UUID event = UUID.fromString("0190c100-0000-7000-8000-0000000000e1");
        UUID owner = UUID.fromString("0190c100-0000-7000-8000-000000000001");
        String insert =
                """
                insert into kernel.notification_log (
                    notification_id, rule_id, event_id, owner_entity_id, recipient_hash, channel, status, attempts
                ) values (?, ?, ?, ?, ?, 'SMS', 'SENT', 1)
                """;
        // Two recipients of one event under one rule: the unique key told them apart by the hash.
        admin.update(insert, UUID.fromString("0190c100-0000-7000-8000-0000000000b1"), rule, event, owner, LEGACY_ONE);
        admin.update(insert, UUID.fromString("0190c100-0000-7000-8000-0000000000b2"), rule, event, owner, LEGACY_TWO);

        kernel(url).load().migrate();

        List<Map<String, Object>> rows =
                admin.queryForList("select recipient_hash, recipient_hash_key_id, recipient_entity_id, audience_role"
                        + " from kernel.notification_log order by notification_id");
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(String.valueOf(row.get("recipient_hash")))
                    .matches("[0-9a-f]{64}")
                    .isNotIn(LEGACY_ONE, LEGACY_TWO);
            assertThat(row.get("recipient_hash_key_id")).isNull();
            assertThat(row.get("recipient_entity_id")).isNull();
            assertThat(row.get("audience_role")).isNull();
        });
        assertThat(rows.get(0).get("recipient_hash")).isNotEqualTo(rows.get(1).get("recipient_hash"));

        // The policies are forced again: the migration lifted FORCE for its one statement only.
        assertThat(admin.queryForObject(
                        "select relforcerowsecurity from pg_class where oid = 'kernel.notification_log'::regclass",
                        Boolean.class))
                .isTrue();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration kernel(String url) {
        return Flyway.configure()
                .dataSource(url, "coop_migrator", "coop_migrator")
                .locations("classpath:db/migration/kernel")
                .schemas("kernel")
                .defaultSchema("kernel");
    }
}
