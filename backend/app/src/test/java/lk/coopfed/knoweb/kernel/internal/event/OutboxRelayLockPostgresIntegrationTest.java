package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboxRelayLockPostgresIntegrationTest extends PostgresIntegrationTest {

    @BeforeEach
    void cleanOutbox() {

        superuserJdbc().execute("TRUNCATE TABLE kernel.event_outbox");
    }

    @Test
    void twoRelayInstancesCannotPublishTheSameSourceConcurrently() throws Exception {

        insert(1, UUID.randomUUID());

        insert(2, UUID.randomUUID());

        CountDownLatch firstPublishStarted = new CountDownLatch(1);

        CountDownLatch releaseFirst = new CountDownLatch(1);

        List<Long> published = new CopyOnWriteArrayList<>();

        BrokerAdapter broker = message -> {
            published.add(message.sourceSeq());

            if (message.sourceSeq() == 1L) {

                firstPublishStarted.countDown();

                try {
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) {

                        throw new IllegalStateException("Timed out waiting to release relay");
                    }

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();

                    throw new IllegalStateException(e);
                }
            }
        };

        HikariDataSource firstDataSource =
                OutboxRelay.openDataSource(POSTGRES.getJdbcUrl(), "coop_relay", "coop_relay", 2);

        HikariDataSource secondDataSource =
                OutboxRelay.openDataSource(POSTGRES.getJdbcUrl(), "coop_relay", "coop_relay", 2);

        OutboxRelay first = new OutboxRelay(firstDataSource, 500, broker);

        OutboxRelay second = new OutboxRelay(secondDataSource, 500, broker);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {

            Future<Integer> firstResult = executor.submit(first::relayOnce);

            assertThat(firstPublishStarted.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(second.relayOnce()).isZero();

            releaseFirst.countDown();

            assertThat(firstResult.get(10, TimeUnit.SECONDS)).isEqualTo(2);

        } finally {

            releaseFirst.countDown();

            executor.shutdownNow();

            first.close();
            second.close();
        }

        assertThat(published).containsExactly(1L, 2L);

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT count(*)
                                        FROM kernel.event_outbox
                                        WHERE published_at IS NOT NULL
                                        """,
                                Integer.class))
                .isEqualTo(2);
    }

    private static void insert(long sourceSeq, UUID aggregateId) {

        superuserJdbc()
                .update(
                        """
                        INSERT INTO kernel.event_outbox (
                            event_id,
                            event_type,
                            occurred_at,
                            source,
                            source_seq,
                            owner_entity_id,
                            aggregate_type,
                            aggregate_id,
                            correlation_id,
                            payload
                        )
                        VALUES (
                            ?,
                            'hello.greeting.registered.v1',
                            now(),
                            'central',
                            ?,
                            ?,
                            'greeting',
                            ?,
                            ?,
                            '{}'::jsonb
                        )
                        """,
                        UUID.randomUUID(),
                        sourceSeq,
                        UUID.randomUUID(),
                        aggregateId,
                        UUID.randomUUID());
    }
}
