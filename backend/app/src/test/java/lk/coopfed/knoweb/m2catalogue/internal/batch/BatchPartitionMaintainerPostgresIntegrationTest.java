package lk.coopfed.knoweb.m2catalogue.internal.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lk.coopfed.knoweb.kernel.internal.audit.PartitionJob;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The batch partitions are kept ahead by the kernel's partition-maintenance job through M2's
 * {@link BatchPartitionMaintainer} (review of 26 September: nothing called
 * {@code ensure_batch_partitions} after V0001). The maintainer runs on the application's
 * connection, as the job does; the function is SECURITY DEFINER for that.
 */
class BatchPartitionMaintainerPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String THIRD_MONTH =
            "batch_" + YearMonth.now(ZoneOffset.UTC).plusMonths(3).format(DateTimeFormatter.ofPattern("yyyy_MM"));

    @Autowired
    private BatchPartitionMaintainer maintainer;

    @Autowired
    private PartitionJob partitionJob;

    @AfterEach
    void thePartitionsAreBackAsTheMigrationLeftThem() {
        superuserJdbc().execute("select catalogue.ensure_batch_partitions(3)");
    }

    @Test
    void theMaintainerCreatesTheMissingMonthAndNothingWhenAllAreThere() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("drop table if exists catalogue." + THIRD_MONTH);

        assertThat(maintainer.table()).isEqualTo("catalogue.batch");
        assertThat(maintainer.ensurePartitions()).isEqualTo(1);
        assertThat(partitions(admin)).contains(THIRD_MONTH);
        assertThat(admin.queryForObject(
                        "select relforcerowsecurity from pg_class where oid = ?::regclass",
                        Boolean.class,
                        "catalogue." + THIRD_MONTH))
                .isTrue();

        assertThat(maintainer.ensurePartitions()).isZero();
    }

    @Test
    void theKernelsPartitionJobCallsIt() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("drop table if exists catalogue." + THIRD_MONTH);

        assertThat(partitionJob.createUpcomingPartitions()).isEqualTo(1);

        assertThat(partitions(admin)).contains(THIRD_MONTH);
    }

    private static List<String> partitions(JdbcTemplate admin) {
        return admin.queryForList(
                """
                select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid
                 where i.inhparent = 'catalogue.batch'::regclass order by 1
                """,
                String.class);
    }
}
