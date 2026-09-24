package lk.coopfed.knoweb.kernel.internal.event;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@ConditionalOnBean(BrokerAdapter.class)
public class Replayer {

    private final JdbcTemplate jdbc;
    private final BrokerAdapter broker;

    @Autowired
    public Replayer(@Qualifier("relayDataSource") DataSource relayDataSource, BrokerAdapter broker) {

        this.jdbc = new JdbcTemplate(relayDataSource);
        this.broker = broker;
    }

    Replayer(DataSource relayDataSource, BrokerAdapter broker, boolean testConstructor) {

        this.jdbc = new JdbcTemplate(relayDataSource);
        this.broker = broker;
    }

    public int replay(String consumer, long fromSeq) {

        if (consumer == null || consumer.isBlank()) {
            throw new IllegalArgumentException("Consumer must not be blank");
        }

        if (fromSeq < 0) {
            throw new IllegalArgumentException("fromSeq must not be negative");
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
                        WHERE source = 'central'
                          AND source_seq >= ?
                        ORDER BY source_seq
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
                fromSeq);

        for (OutboxMessage message : messages) {
            broker.publishToConsumer(consumer, message);
        }

        return messages.size();
    }
}
