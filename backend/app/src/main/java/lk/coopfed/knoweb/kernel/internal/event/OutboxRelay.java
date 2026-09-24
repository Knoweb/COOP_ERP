package lk.coopfed.knoweb.kernel.internal.event;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.util.List;
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

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BrokerAdapter broker;
    private final int batchSize;

    public OutboxRelay(
            @Value("${coop-erp.relay.url:jdbc:postgresql://localhost:5434/coop_erp}") String url,
            @Value("${coop-erp.relay.user:coop_relay}") String user,
            @Value("${coop-erp.relay.password:coop_relay}") String password,
            @Value("${coop-erp.relay.pool-size:2}") int poolSize,
            @Value("${coop-erp.relay.batch-size:500}") int batchSize,
            BrokerAdapter broker) {

        this(openDataSource(url, user, password, poolSize), batchSize, broker);
    }

    OutboxRelay(HikariDataSource dataSource, int batchSize, BrokerAdapter broker) {

        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);

        this.transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        this.batchSize = batchSize;
        this.broker = broker;
    }

    static HikariDataSource openDataSource(String url, String user, String password, int poolSize) {

        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        config.setPoolName("coop-relay");

        return new HikariDataSource(config);
    }

    @Scheduled(
            fixedDelayString = "${coop-erp.relay.poll-delay-ms:500}",
            initialDelayString = "${coop-erp.relay.initial-delay-ms:1000}")
    public void scheduledRelay() {
        relayOnce();
    }

    public int relayOnce() {

        Integer result = transaction.execute(status -> {
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
                                            ORDER BY
                                                source,
                                                source_seq
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
    @PreDestroy
    public void close() {
        dataSource.close();
    }
}
