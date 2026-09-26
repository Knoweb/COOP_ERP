package lk.coopfed.knoweb.kernel.internal.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * kernel/V0056: the partition functions leave a policy alone when it already carries the class
 * test of CR-17A-3, and recreate it only when it is missing or ungated. A recreated policy
 * takes an ACCESS EXCLUSIVE lock on the partition every command writes to, so a nightly run
 * that recreated every policy queued every audit and outbox insert behind any long
 * transaction (review of 26 Sep).
 */
class PartitionPoliciesPostgresIntegrationTest extends PostgresIntegrationTest {

    @Test
    void theAuditPartitionFunctionLeavesAGatedPolicyAlone() {
        JdbcTemplate admin = superuserJdbc();
        String partition = "audit_event_"
                + admin.queryForObject(
                        "select to_char(date_trunc('month', current_timestamp at time zone 'UTC'), 'YYYY_MM')",
                        String.class);

        admin.execute("select kernel.ensure_audit_partitions(0)");
        Map<String, Long> before = policyOids(partition, "own_read", "own_write", "fed_view", "ext_view");

        admin.execute("select kernel.ensure_audit_partitions(0)");
        assertThat(policyOids(partition, "own_read", "own_write", "fed_view", "ext_view"))
                .isEqualTo(before);

        // A partition made by the text of V0030: the ungated policy is put right on the next run.
        admin.execute("drop policy own_write on kernel." + partition);
        admin.execute("create policy own_write on kernel." + partition
                + " for insert to app_rw with check (owner_entity_id = kernel.scope_entity())");
        admin.execute("select kernel.ensure_audit_partitions(0)");

        assertThat(withCheck(partition, "own_write")).contains("kernel.scope_class() = 'OWN'::text");
        assertThat(policyOids(partition, "own_read")).isEqualTo(policyOids(before, "own_read"));
    }

    @Test
    void theOutboxPartitionFunctionLeavesAGatedPolicyAlone() {
        JdbcTemplate admin = superuserJdbc();
        String partition = "event_outbox_"
                + admin.queryForObject(
                        "select to_char(date_trunc('month', current_timestamp at time zone 'UTC'), 'YYYYMM')",
                        String.class);

        admin.execute("select kernel.ensure_event_outbox_partitions(0)");
        Map<String, Long> before = policyOids(
                partition, "event_outbox_app_insert", "event_outbox_relay_select", "event_outbox_relay_update");

        admin.execute("select kernel.ensure_event_outbox_partitions(0)");
        assertThat(policyOids(
                        partition, "event_outbox_app_insert", "event_outbox_relay_select", "event_outbox_relay_update"))
                .isEqualTo(before);

        admin.execute("drop policy event_outbox_app_insert on kernel." + partition);
        admin.execute("create policy event_outbox_app_insert on kernel." + partition
                + " for insert to app_rw with check (owner_entity_id = kernel.scope_entity())");
        admin.execute("select kernel.ensure_event_outbox_partitions(0)");

        assertThat(withCheck(partition, "event_outbox_app_insert")).contains("kernel.scope_class() = 'OWN'::text");
    }

    /** The catalogue oid of each named policy on the partition: a recreated policy gets a new one. */
    private static Map<String, Long> policyOids(String partition, String... names) {
        List<Map<String, Object>> rows = superuserJdbc()
                .queryForList(
                        "select polname, oid from pg_policy where polrelid = ?::regclass"
                                + " and polname = any (string_to_array(?, ','))",
                        "kernel." + partition,
                        String.join(",", names));
        return rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row.get("polname"), row -> ((Number) row.get("oid")).longValue()));
    }

    private static Map<String, Long> policyOids(Map<String, Long> all, String name) {
        return Map.of(name, all.get(name));
    }

    private static String withCheck(String partition, String policy) {
        return superuserJdbc()
                .queryForObject(
                        "select with_check from pg_policies where schemaname = 'kernel' and tablename = ?"
                                + " and policyname = ?",
                        String.class,
                        partition,
                        policy);
    }
}
