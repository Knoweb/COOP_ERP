package lk.coopfed.knoweb.kernel.internal.event;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("worker")
@ConditionalOnBean(BrokerAdapter.class)
public class OutboxRelay implements AutoCloseable {

    private final HikariDataSource ownedDataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BrokerAdapter broker;
    private final int batchSize;

    @Autowired
    public OutboxRelay(
            @Qualifier("relayDataSource") DataSource dataSource,
            @Value("${coop-erp.relay.batch-size:500}") int batchSize,
            BrokerAdapter broker) {

        this.ownedDataSource = null;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.batchSize = batchSize;
        this.broker = broker;
    }

    OutboxRelay(HikariDataSource dataSource, int batchSize, BrokerAdapter broker) {

        this.ownedDataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.batchSize = batchSize;
        this.broker = broker;
    }

    static HikariDataSource openDataSource(String url, String user, String password, int poolSize) {

        return RelayDataSourceConfiguration.create(url, user, password, poolSize);
    }

    @Scheduled(
            fixedDelayString = "${coop-erp.relay.poll-delay-ms:500}",
            initialDelayString = "${coop-erp.relay.initial-delay-ms:1000}")
    public void scheduledRelay() {
        relayOnce();
    }

    public int relayOnce() {

        Integer result = transaction.execute(status -> {
            List<String> sources = jdbc.queryForList(
                    """
                                            SELECT source
                                              FROM kernel.event_outbox
                                             WHERE published_at IS NULL
                                             ORDER BY source, source_seq
                                             LIMIT 1
                                            """,
                    String.class);

            if (sources.isEmpty()) {
                return 0;
            }

            String source = sources.getFirst();

            Boolean locked = jdbc.queryForObject(
                    """
                                            SELECT pg_try_advisory_xact_lock(
                                                hashtextextended(?, 0)
                                            )
                                            """,
                    Boolean.class,
                    "outbox-relay:" + source);

            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }

            List<OutboxMessage> messages = jdbc.query(
                    """
                                            SELECT
                                                event_id,
                                                event_type,
                                                occurred_at,
                                                source,
                                                source_seq,
                                                owner_entity_id,
                                                location_id,
                                                aggregate_type,
                                                aggregate_id,
                                                correlation_id,
                                                causation_id,
                                                actor_user_id,
                                                engine_version,
                                                payload::text AS payload
                                            FROM kernel.event_outbox
                                            WHERE published_at IS NULL
                                              AND source = ?
                                            ORDER BY source_seq
                                            LIMIT ?
                                            FOR UPDATE SKIP LOCKED
                                            """,
                    (rs, rowNum) -> new OutboxMessage(
                            rs.getObject("event_id", java.util.UUID.class),
                            rs.getString("event_type"),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getString("source"),
                            rs.getLong("source_seq"),
                            rs.getObject("owner_entity_id", java.util.UUID.class),
                            rs.getObject("location_id", java.util.UUID.class),
                            rs.getString("aggregate_type"),
                            rs.getObject("aggregate_id", java.util.UUID.class),
                            rs.getObject("correlation_id", java.util.UUID.class),
                            rs.getObject("causation_id", java.util.UUID.class),
                            rs.getObject("actor_user_id", java.util.UUID.class),
                            rs.getString("engine_version"),
                            rs.getString("payload")),
                    source,
                    batchSize);

            int published = 0;

            for (OutboxMessage message : messages) {

                broker.publish(message);

                int updated = jdbc.update(
                        """
                                                UPDATE kernel.event_outbox
                                                   SET published_at = now()
                                                 WHERE received_at = (
                                                     SELECT received_at
                                                       FROM kernel.event_outbox
                                                      WHERE event_id = ?
                                                      ORDER BY received_at DESC
                                                      LIMIT 1
                                                 )
                                                   AND event_id = ?
                                                   AND published_at IS NULL
                                                """,
                        message.eventId(),
                        message.eventId());

                if (updated != 1) {
                    throw new IllegalStateException("Outbox event " + message.eventId() + " was not marked published");
                }

                published++;
            }

            return published;
        });

        return result == null ? 0 : result;
    }

    @Override
    public void close() {

        if (ownedDataSource != null) {
            ownedDataSource.close();
        }
    }
}
