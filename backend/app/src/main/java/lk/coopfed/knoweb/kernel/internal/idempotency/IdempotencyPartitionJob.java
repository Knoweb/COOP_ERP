package lk.coopfed.knoweb.kernel.internal.idempotency;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maintains daily partitions for kernel.idempotency_key.
 *
 * <p>Runs with migrator rights because expiry is performed by dropping old
 * partitions. app_rw is never granted DELETE or TRUNCATE.
 */
@Component
public class IdempotencyPartitionJob implements ApplicationRunner {

    private static final long ADVISORY_LOCK = 3_031_001L;

    private static final Pattern PARTITION_NAME = Pattern.compile("idempotency_key_(\\d{8})");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    private final int retentionHours;
    private final int daysAhead;

    public IdempotencyPartitionJob(
            @Value("${coop-erp.migration.url}") String url,
            @Value("${coop-erp.migration.user}") String user,
            @Value("${coop-erp.migration.password}") String password,
            @Value("${coop-erp.idempotency.retention-hours:24}") int retentionHours,
            @Value("${coop-erp.idempotency.partition-days-ahead:2}") int daysAhead) {

        DriverManagerDataSource migrator = new DriverManagerDataSource(url, user, password);

        this.jdbc = new JdbcTemplate(migrator);
        this.transaction = new TransactionTemplate(new JdbcTransactionManager(migrator));

        this.retentionHours = retentionHours;
        this.daysAhead = daysAhead;
    }

    @Override
    public void run(ApplicationArguments args) {
        maintain();
    }

    @Scheduled(cron = "${coop-erp.idempotency.partition-cron:0 5 0 * * *}", zone = "UTC")
    public void scheduledMaintenance() {
        maintain();
    }

    void maintain() {
        transaction.executeWithoutResult(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK);

            if (!Boolean.TRUE.equals(locked)) {
                return;
            }

            LocalDate today = LocalDate.now(ZoneOffset.UTC);

            createPartition(today.minusDays(1));

            for (int offset = 0; offset <= daysAhead; offset++) {
                createPartition(today.plusDays(offset));
            }

            dropExpiredPartitions(today);
        });
    }

    private void createPartition(LocalDate date) {
        String name = "idempotency_key_" + date.toString().replace("-", "");

        LocalDate until = date.plusDays(1);

        jdbc.execute("CREATE TABLE IF NOT EXISTS kernel."
                + name
                + " PARTITION OF kernel.idempotency_key "
                + "FOR VALUES FROM ('"
                + date
                + "') TO ('"
                + until
                + "')");

        jdbc.execute("ALTER TABLE kernel." + name + " ENABLE ROW LEVEL SECURITY");

        jdbc.execute("ALTER TABLE kernel." + name + " FORCE ROW LEVEL SECURITY");

        jdbc.execute("DROP POLICY IF EXISTS idempotency_key_read ON kernel." + name);

        jdbc.execute("CREATE POLICY idempotency_key_read ON kernel."
                + name
                + " FOR SELECT USING ("
                + "user_id = "
                + "NULLIF(current_setting('app.user_id', true), '')::uuid"
                + ")");

        jdbc.execute("DROP POLICY IF EXISTS idempotency_key_insert ON kernel." + name);

        jdbc.execute("CREATE POLICY idempotency_key_insert ON kernel."
                + name
                + " FOR INSERT WITH CHECK ("
                + "user_id = "
                + "NULLIF(current_setting('app.user_id', true), '')::uuid"
                + ")");

        jdbc.execute("DROP POLICY IF EXISTS idempotency_key_update ON kernel." + name);

        jdbc.execute("CREATE POLICY idempotency_key_update ON kernel."
                + name
                + " FOR UPDATE "
                + "USING ("
                + "user_id = "
                + "NULLIF(current_setting('app.user_id', true), '')::uuid"
                + ") "
                + "WITH CHECK ("
                + "user_id = "
                + "NULLIF(current_setting('app.user_id', true), '')::uuid"
                + ")");

        jdbc.execute("REVOKE DELETE, TRUNCATE ON kernel." + name + " FROM app_rw");
    }

    private void dropExpiredPartitions(LocalDate today) {
        int retainedDays = Math.max(1, (int) Math.ceil(retentionHours / 24.0));

        LocalDate oldestDateToKeep = today.minusDays(retainedDays);

        List<String> partitions = jdbc.queryForList(
                """
                        SELECT child.relname
                          FROM pg_inherits inheritance
                          JOIN pg_class parent
                            ON parent.oid = inheritance.inhparent
                          JOIN pg_namespace parent_namespace
                            ON parent_namespace.oid = parent.relnamespace
                          JOIN pg_class child
                            ON child.oid = inheritance.inhrelid
                         WHERE parent_namespace.nspname = 'kernel'
                           AND parent.relname = 'idempotency_key'
                        """,
                String.class);

        for (String partition : partitions) {
            Matcher matcher = PARTITION_NAME.matcher(partition);

            if (!matcher.matches()) {
                continue;
            }

            String value = matcher.group(1);

            LocalDate partitionDate = LocalDate.of(
                    Integer.parseInt(value.substring(0, 4)),
                    Integer.parseInt(value.substring(4, 6)),
                    Integer.parseInt(value.substring(6, 8)));

            if (partitionDate.isBefore(oldestDateToKeep)) {
                jdbc.execute("DROP TABLE IF EXISTS kernel." + partition);
            }
        }
    }
}
