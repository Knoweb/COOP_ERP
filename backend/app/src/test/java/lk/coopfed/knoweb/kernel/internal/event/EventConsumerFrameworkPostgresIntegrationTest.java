package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.hello.api.GreetingRegistered;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class EventConsumerFrameworkPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    AuditFacade audit;

    @Autowired
    InboxGuard inbox;

    @BeforeEach
    void cleanTables() {

        superuserJdbc()
                .execute(
                        """
                TRUNCATE TABLE
                    kernel.event_inbox,
                    kernel.event_outbox
                """);

        superuserJdbc()
                .execute(
                        """
                ALTER SEQUENCE
                    kernel.central_source_seq
                RESTART WITH 1
                """);
    }

    @Test
    void duplicateDeliveryAppliesConsumerExactlyOnce() throws Exception {

        TestProjection projection = new TestProjection();

        CapturingBroker broker = new CapturingBroker();

        EventConsumerDispatcher dispatcher = dispatcher(projection, broker);

        OutboxMessage message = message(1, UUID.randomUUID());

        assertThat(dispatcher.deliver("test.projection", message, 1))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.APPLIED);

        assertThat(dispatcher.deliver("test.projection", message, 1))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.DUPLICATE);

        assertThat(projection.applied.get()).isEqualTo(1);

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT count(*)
                                        FROM kernel.event_inbox
                                        WHERE consumer = 'test.projection'
                                          AND outcome = 'APPLIED'
                                        """,
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void thirdFailureDeadLettersAndWritesAlertAudit() throws Exception {

        TestProjection projection = new TestProjection();

        projection.fail = true;

        CapturingBroker broker = new CapturingBroker();

        EventConsumerDispatcher dispatcher = dispatcher(projection, broker);

        OutboxMessage message = message(1, UUID.randomUUID());

        assertThat(dispatcher.deliver("test.projection", message, 1))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.RETRY);

        assertThat(dispatcher.deliver("test.projection", message, 2))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.RETRY);

        assertThat(dispatcher.deliver("test.projection", message, 3))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.DEAD_LETTERED);

        assertThat(broker.deadLetters.get()).isEqualTo(1);

        assertThat(broker.lastQueue).isEqualTo(DeadLetter.QUEUE);

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT outcome
                                        FROM kernel.event_inbox
                                        WHERE consumer = 'test.projection'
                                          AND event_id = ?
                                        """,
                                String.class,
                                message.eventId()))
                .isEqualTo("FAILED");

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT count(*)
                                        FROM kernel.audit_event
                                        WHERE event_type_code =
                                            'EVENT_CONSUMER_DEAD_LETTERED'
                                          AND subject_id = ?
                                        """,
                                Integer.class,
                                message.eventId()))
                .isEqualTo(1);
    }

    @Test
    void replayRebuildsProjectionFromArchive() throws Exception {

        UUID first = UUID.randomUUID();

        UUID second = UUID.randomUUID();

        insertArchive(message(1, first));

        insertArchive(message(2, second));

        TestProjection projection = new TestProjection();

        CapturingBroker broker = new CapturingBroker();

        EventConsumerDispatcher dispatcher = dispatcher(projection, broker);

        broker.dispatcher = dispatcher;

        HikariDataSource relayDataSource =
                OutboxRelay.openDataSource(POSTGRES.getJdbcUrl(), "coop_relay", "coop_relay", 2);

        try {

            EventConsumerRegistry registry = new EventConsumerRegistry();
            registry.register(projection);

            Replayer replayer = new Replayer(relayDataSource, broker, registry, true);

            assertThat(replayer.replay("test.projection", 1)).isEqualTo(2);

        } finally {
            relayDataSource.close();
        }

        assertThat(projection.applied.get()).isEqualTo(2);

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT count(*)
                                        FROM kernel.event_inbox
                                        WHERE consumer = 'test.projection'
                                          AND outcome = 'APPLIED'
                                        """,
                                Integer.class))
                .isEqualTo(2);
    }

    private EventConsumerDispatcher dispatcher(TestProjection projection, CapturingBroker broker) {

        EventConsumerRegistry registry = new EventConsumerRegistry();

        registry.register(projection);

        return new EventConsumerDispatcher(
                registry, inbox, new DeadLetter(broker), audit, mapper, jdbc, transactionManager);
    }

    private OutboxMessage message(long sourceSeq, UUID greetingId) throws Exception {

        UUID entityId = UUID.randomUUID();

        GreetingRegistered payload = new GreetingRegistered(greetingId, entityId);

        return new OutboxMessage(
                UUID.randomUUID(),
                GreetingRegistered.TYPE,
                Instant.now(),
                "central",
                sourceSeq,
                entityId,
                null,
                "greeting",
                greetingId,
                UUID.randomUUID(),
                null,
                null,
                null,
                mapper.writeValueAsString(payload));
    }

    private void insertArchive(OutboxMessage message) {

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
                            location_id,
                            aggregate_type,
                            aggregate_id,
                            correlation_id,
                            causation_id,
                            actor_user_id,
                            engine_version,
                            payload,
                            published_at
                        )
                        VALUES (
                            ?,
                            ?,
                            now(),
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            CAST(? AS jsonb),
                            now()
                        )
                        """,
                        message.eventId(),
                        message.eventType(),
                        message.source(),
                        message.sourceSeq(),
                        message.ownerEntityId(),
                        message.locationId(),
                        message.aggregateType(),
                        message.aggregateId(),
                        message.correlationId(),
                        message.causationId(),
                        message.actorUserId(),
                        message.engineVersion(),
                        message.payload());
    }

    static final class TestProjection {

        final AtomicInteger applied = new AtomicInteger();

        volatile boolean fail;

        @EventConsumer(
                types = {GreetingRegistered.TYPE},
                consumer = "test.projection")
        public void onGreeting(GreetingRegistered event, ScopeContext scope) {

            if (fail) {
                throw new IllegalStateException("projection failure");
            }

            assertThat(scope.entityId()).isEqualTo(event.ownerEntityId());

            applied.incrementAndGet();
        }
    }

    static final class CapturingBroker implements BrokerAdapter {

        final AtomicInteger deadLetters = new AtomicInteger();

        volatile String lastQueue;

        volatile EventConsumerDispatcher dispatcher;

        @Override
        public void publish(OutboxMessage message) {
            // Not needed in these consumer tests.
        }

        @Override
        public void publishToConsumer(String consumer, OutboxMessage message) {

            if (dispatcher != null) {
                dispatcher.deliver(consumer, message, 1);
            }
        }

        @Override
        public void deadLetter(String queue, String consumer, OutboxMessage message, int attempts, String error) {

            lastQueue = queue;
            deadLetters.incrementAndGet();
        }
    }
}
