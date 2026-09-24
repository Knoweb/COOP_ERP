package lk.coopfed.knoweb.kernel.internal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AuditPermissionsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A = UUID.fromString("0190f000-0000-7000-8000-0000000000a1");

    private static final UUID ENTITY_B = UUID.fromString("0190f000-0000-7000-8000-0000000000b1");

    private static final String CONTROL_PARTITION = "audit_event_rls_control";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void removeControlPartitionAndTemporaryGrant() {
        JdbcTemplate admin = superuserJdbc();

        admin.execute("REVOKE SELECT ON kernel.audit_event FROM app_rw");
        admin.execute("DROP TABLE IF EXISTS kernel." + CONTROL_PARTITION);
    }

    @Test
    void appRwCannotUpdateDeleteOrTruncateParentOrPartition() {
        JdbcTemplate admin = superuserJdbc();

        String partition = admin.queryForObject(
                """
                        SELECT child.relname
                          FROM pg_inherits i
                          JOIN pg_class parent
                            ON parent.oid = i.inhparent
                          JOIN pg_class child
                            ON child.oid = i.inhrelid
                         WHERE parent.oid = 'kernel.audit_event'::regclass
                         ORDER BY child.relname
                         LIMIT 1
                        """,
                String.class);

        assertThat(partition).isNotBlank();

        assertThatThrownBy(() -> jdbc.update("UPDATE kernel.audit_event " + "SET reason_text = reason_text"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM kernel.audit_event")).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.execute("TRUNCATE kernel.audit_event")).isInstanceOf(DataAccessException.class);

        String child = "kernel." + partition;

        assertThatThrownBy(() -> jdbc.update("UPDATE " + child + " SET reason_text = reason_text"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + child)).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.execute("TRUNCATE " + child)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void parentRlsProtectsRowsInLaterPartitionWithoutChildPolicies() {
        JdbcTemplate admin = superuserJdbc();

        YearMonth month = YearMonth.now(ZoneOffset.UTC).plusMonths(6);
        OffsetDateTime from = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        admin.execute("DROP TABLE IF EXISTS kernel." + CONTROL_PARTITION);

        admin.execute(
                """
                CREATE TABLE kernel.%s
                PARTITION OF kernel.audit_event
                FOR VALUES FROM ('%s') TO ('%s')
                """
                        .formatted(CONTROL_PARTITION, from, to));

        Boolean childRls = admin.queryForObject(
                """
                        SELECT relrowsecurity
                          FROM pg_class
                         WHERE oid = ?::regclass
                        """,
                Boolean.class,
                "kernel." + CONTROL_PARTITION);

        assertThat(childRls).isFalse();

        UUID subject = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        inScope(ENTITY_A, "FEDERATION_VIEW", () -> {
            jdbc.update(
                    """
                            INSERT INTO kernel.audit_event (
                                audit_id,
                                event_type_code,
                                occurred_at,
                                received_at,
                                owner_entity_id,
                                subject_table,
                                subject_id,
                                correlation_id
                            )
                            VALUES (
                                ?,
                                'HELLO_GREETING_REGISTERED',
                                now(),
                                ?,
                                ?,
                                'rls_control',
                                ?,
                                ?
                            )
                            """,
                    UUID.randomUUID(),
                    from.plusHours(12),
                    ENTITY_A,
                    subject,
                    correlation);

            return null;
        });

        admin.execute("GRANT SELECT ON kernel.audit_event TO app_rw");

        Integer ownCount = inScope(
                ENTITY_A,
                "OWN",
                () -> jdbc.queryForObject(
                        """
                                        SELECT count(*)
                                          FROM kernel.audit_event
                                         WHERE subject_id = ?
                                        """,
                        Integer.class,
                        subject));

        Integer otherCount = inScope(
                ENTITY_B,
                "OWN",
                () -> jdbc.queryForObject(
                        """
                                        SELECT count(*)
                                          FROM kernel.audit_event
                                         WHERE subject_id = ?
                                        """,
                        Integer.class,
                        subject));

        assertThat(ownCount).isEqualTo(1);
        assertThat(otherCount).isZero();
    }

    private <T> T inScope(UUID entityId, String policyClass, Supplier<T> work) {

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        return transaction.execute(status -> {
            jdbc.queryForObject(
                    "SELECT set_config(" + "'app.scope_entity_id', ?, true)", String.class, entityId.toString());

            jdbc.queryForObject("SELECT set_config(" + "'app.scope_location_id', '', true)", String.class);

            jdbc.queryForObject("SELECT set_config(" + "'app.scope_class', ?, true)", String.class, policyClass);

            return work.get();
        });
    }
}
