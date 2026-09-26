package lk.coopfed.knoweb.kernel.internal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.PartitionMaintainer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The partition-maintenance job keeps the kernel's own partitions first, then every table a
 * module registered through {@link PartitionMaintainer}, and reports what the modules created.
 */
class PartitionJobTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void theKernelsOwnTablesFirstThenEveryRegisteredMaintainer() {
        List<String> calls = new ArrayList<>();
        PartitionJob job = new PartitionJob(
                jdbc, List.of(maintainer("catalogue.batch", 2, calls), maintainer("pos.receipt", 0, calls)));

        int created = job.createUpcomingPartitions();

        InOrder kernelFirst = inOrder(jdbc);
        kernelFirst.verify(jdbc).execute("select kernel.ensure_audit_partitions(3)");
        kernelFirst.verify(jdbc).execute("select kernel.ensure_event_outbox_partitions(3)");
        assertThat(calls).containsExactly("catalogue.batch", "pos.receipt");
        assertThat(created).isEqualTo(2);
    }

    @Test
    void withNoMaintainerTheJobKeepsTheKernelsTablesAlone() {
        assertThat(new PartitionJob(jdbc, List.of()).createUpcomingPartitions()).isZero();
    }

    private static PartitionMaintainer maintainer(String table, int created, List<String> calls) {
        return new PartitionMaintainer() {
            @Override
            public String table() {
                return table;
            }

            @Override
            public int ensurePartitions() {
                calls.add(table);
                return created;
            }
        };
    }
}
