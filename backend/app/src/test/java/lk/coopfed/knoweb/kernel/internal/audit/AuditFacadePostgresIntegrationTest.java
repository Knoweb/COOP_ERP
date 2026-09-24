package lk.coopfed.knoweb.kernel.internal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AuditFacadePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A = UUID.fromString("0190a000-0000-7000-8000-000000000001");

    private static final UUID ENTITY_B = UUID.fromString("0190a000-0000-7000-8000-000000000002");

    private static final UUID USER = UUID.fromString("0190a000-0000-7000-8000-000000000010");

    private static final UUID DEVICE = UUID.fromString("0190a000-0000-7000-8000-000000000020");

    private static final UUID CORRELATION = UUID.fromString("0190a000-0000-7000-8000-000000000030");

    @Autowired
    AuditFacade audit;

    @Autowired
    PartitionJob partitionJob;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanAuditRows() {
        superuserJdbc().execute("truncate table kernel.audit_event");
    }

    @Test
    void facadePersistsMinimalDiffAndScopeIdentity() {
        UUID subjectId = UUID.randomUUID();

        inOwnScope(() -> audit.record(
                "ENTITY_ACTIVATED",
                Subject.of("entity", subjectId),
                Map.of(
                        "status", "PENDING",
                        "unchanged", "same"),
                Map.of(
                        "status", "ACTIVE",
                        "unchanged", "same"),
                scope(),
                "activation approved",
                null));

        JdbcTemplate admin = superuserJdbc();

        assertThat(admin.queryForObject(
                        """
                                select count(*)
                                  from kernel.audit_event
                                 where event_type_code = 'ENTITY_ACTIVATED'
                                   and subject_id = ?
                                """,
                        Integer.class,
                        subjectId))
                .isEqualTo(1);

        assertThat(admin.queryForObject(
                        """
                                select before_state ->> 'status'
                                  from kernel.audit_event
                                 where subject_id = ?
                                """,
                        String.class,
                        subjectId))
                .isEqualTo("PENDING");

        assertThat(admin.queryForObject(
                        """
                                select after_state ->> 'status'
                                  from kernel.audit_event
                                 where subject_id = ?
                                """,
                        String.class,
                        subjectId))
                .isEqualTo("ACTIVE");

        assertThat(admin.queryForObject(
                        """
                                select jsonb_exists(before_state, 'unchanged')
                                  from kernel.audit_event
                                 where subject_id = ?
                                """,
                        Boolean.class,
                        subjectId))
                .isFalse();

        assertThat(admin.queryForObject(
                        """
                                select jsonb_exists(after_state, 'unchanged')
                                  from kernel.audit_event
                                 where subject_id = ?
                                """,
                        Boolean.class,
                        subjectId))
                .isFalse();

        assertThat(admin.queryForObject(
                        """
                                select owner_entity_id
                                  from kernel.audit_event
                                 where subject_id = ?
                                """,
                        UUID.class,
                        subjectId))
                .isEqualTo(ENTITY_A);

        assertThat(admin.queryForObject(
                        """
                                select actor_user_id
                                  from kernel.audit_event
                                 where subject_id = ?
                                """,
                        UUID.class,
                        subjectId))
                .isEqualTo(USER);

        assertThat(admin.queryForObject(
                        """
                                select device_id
                                  from kernel.audit_event
                                 where subject_id = ?
                                """,
                        UUID.class,
                        subjectId))
                .isEqualTo(DEVICE);

        assertThat(admin.queryForObject(
                        """
                                select correlation_id
                                  from kernel.audit_event
                                 where subject_id = ?
                                """,
                        UUID.class,
                        subjectId))
                .isEqualTo(CORRELATION);
    }

    @Test
    void unknownCatalogueTypeIsRejected() {
        assertThatThrownBy(() -> inOwnScope(() -> audit.record(
                        "NOT_IN_CATALOGUE",
                        Subject.of("entity", UUID.randomUUID()),
                        null,
                        Map.of("status", "ACTIVE"),
                        scope())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalogue");
    }

    @Test
    void auditInsertRollsBackWithCallerTransaction() {
        UUID subjectId = UUID.randomUUID();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            setOwnScope();

            audit.record(
                    "ENTITY_ACTIVATED",
                    Subject.of("entity", subjectId),
                    Map.of("status", "PENDING"),
                    Map.of("status", "ACTIVE"),
                    scope());

            status.setRollbackOnly();
        });

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        select count(*)
                                          from kernel.audit_event
                                         where subject_id = ?
                                        """,
                                Integer.class,
                                subjectId))
                .isZero();
    }

    @Test
    void appRwHasInsertOnlyOnAuditEvent() {
        List<String> grants = superuserJdbc()
                .queryForList(
                        """
                                select privilege_type
                                  from information_schema.role_table_grants
                                 where grantee = 'app_rw'
                                   and table_schema = 'kernel'
                                   and table_name = 'audit_event'
                                 order by privilege_type
                                """,
                        String.class);

        assertThat(grants).containsExactly("INSERT");
    }

    @Test
    void laterCreatedMonthlyPartitionStillEnforcesRls() {
        YearMonth month = YearMonth.now(ZoneOffset.UTC).plusMonths(3);

        String partition = "audit_event_" + month.getYear() + "_" + String.format("%02d", month.getMonthValue());

        JdbcTemplate admin = superuserJdbc();

        admin.execute("drop table if exists kernel." + partition);

        partitionJob.createUpcomingPartitions();

        Boolean rlsEnabled = admin.queryForObject(
                """
                        select c.relrowsecurity
                          from pg_catalog.pg_class c
                          join pg_catalog.pg_namespace n
                            on n.oid = c.relnamespace
                         where n.nspname = 'kernel'
                           and c.relname = ?
                        """,
                Boolean.class,
                partition);

        Boolean rlsForced = admin.queryForObject(
                """
                        select c.relforcerowsecurity
                          from pg_catalog.pg_class c
                          join pg_catalog.pg_namespace n
                            on n.oid = c.relnamespace
                         where n.nspname = 'kernel'
                           and c.relname = ?
                        """,
                Boolean.class,
                partition);

        assertThat(rlsEnabled).isTrue();
        assertThat(rlsForced).isTrue();

        assertThat(admin.queryForObject(
                        """
                                select count(*)
                                  from pg_catalog.pg_policy p
                                  join pg_catalog.pg_class c
                                    on c.oid = p.polrelid
                                  join pg_catalog.pg_namespace n
                                    on n.oid = c.relnamespace
                                 where n.nspname = 'kernel'
                                   and c.relname = ?
                                   and p.polname in (
                                       'own_read',
                                       'own_write',
                                       'fed_view',
                                       'ext_view'
                                   )
                                """,
                        Integer.class,
                        partition))
                .isEqualTo(4);

        Instant receivedAt = month.atDay(15).atTime(12, 0).toInstant(ZoneOffset.UTC);

        String insertSql = "insert into kernel."
                + partition
                + """
                         (
                            audit_id,
                            event_type_code,
                            occurred_at,
                            received_at,
                            owner_entity_id,
                            subject_table,
                            subject_id,
                            correlation_id
                         )
                         values (
                            gen_random_uuid(),
                            'ENTITY_ACTIVATED',
                            now(),
                            ?,
                            ?,
                            'entity',
                            ?,
                            ?
                         )
                        """;

        assertThatThrownBy(() -> inOwnScope(() -> jdbc.update(
                        insertSql, Timestamp.from(receivedAt), ENTITY_B, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataAccessException.class);

        inOwnScope(() ->
                jdbc.update(insertSql, Timestamp.from(receivedAt), ENTITY_A, UUID.randomUUID(), UUID.randomUUID()));

        assertThat(admin.queryForObject(
                        "select count(*) from kernel." + partition + " where owner_entity_id = ?",
                        Integer.class,
                        ENTITY_A))
                .isEqualTo(1);
    }

    private ScopeContext scope() {
        Scope active = new Scope(ENTITY_A, null);

        return new ScopeContext(
                USER,
                DEVICE,
                ENTITY_A,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                java.util.Locale.ENGLISH,
                CORRELATION);
    }

    private void inOwnScope(Runnable work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            setOwnScope();
            work.run();
        });
    }

    private void setOwnScope() {
        jdbc.queryForObject(
                """
                select set_config(
                    'app.scope_entity_id',
                    ?,
                    true
                )
                """,
                String.class,
                ENTITY_A.toString());

        jdbc.queryForObject(
                """
                select set_config(
                    'app.scope_location_id',
                    '',
                    true
                )
                """,
                String.class);

        jdbc.queryForObject(
                """
                select set_config(
                    'app.scope_class',
                    'OWN',
                    true
                )
                """,
                String.class);

        jdbc.queryForObject(
                """
                select set_config(
                    'app.granted_entities',
                    '{}',
                    true
                )
                """,
                String.class);
    }
}
