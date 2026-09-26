package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.hello.api.GreetingRegistered;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class EventBackbonePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String RELAY_USER = "coop_relay";
    private static final String RELAY_PASSWORD = "coop_relay";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EventPublisher events;

    @Autowired
    InboxGuard inbox;

    @BeforeEach
    void cleanEventBackbone() {

        JdbcTemplate admin = superuserJdbc();

        admin.execute("TRUNCATE TABLE " + "kernel.event_inbox, " + "kernel.event_outbox");

        admin.execute("ALTER SEQUENCE " + "kernel.central_source_seq " + "RESTART WITH 1");
    }

    @Test
    void writerStoresTheSharedEnvelopeAndScopeMetadata() {

        UUID greetingId = UUID.randomUUID();

        UUID correlationId = UUID.randomUUID();

        publish(greetingId, correlationId, false);

        JdbcTemplate admin = superuserJdbc();

        assertThat(admin.queryForObject(
                        """
                                SELECT event_type
                                FROM kernel.event_outbox
                                """,
                        String.class))
                .isEqualTo(GreetingRegistered.TYPE);

        assertThat(admin.queryForObject(
                        """
                                SELECT source
                                FROM kernel.event_outbox
                                """,
                        String.class))
                .isEqualTo("central");

        assertThat(admin.queryForObject(
                        """
                                SELECT source_seq
                                FROM kernel.event_outbox
                                """,
                        Long.class))
                .isEqualTo(1L);

        assertThat(admin.queryForObject(
                        """
                                SELECT correlation_id
                                FROM kernel.event_outbox
                                """,
                        UUID.class))
                .isEqualTo(correlationId);

        assertThat(admin.queryForObject(
                        """
                                SELECT aggregate_id
                                FROM kernel.event_outbox
                                """,
                        UUID.class))
                .isEqualTo(greetingId);

        assertThat(admin.queryForObject(
                        """
                                SELECT payload ->> 'greetingId'
                                FROM kernel.event_outbox
                                """,
                        String.class))
                .isEqualTo(greetingId.toString());
    }

    @Test
    void centralSequenceMayHaveAGapAfterRollback() {

        publish(UUID.randomUUID(), UUID.randomUUID(), true);

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT count(*)
                                        FROM kernel.event_outbox
                                        """,
                                Integer.class))
                .isZero();

        publish(UUID.randomUUID(), UUID.randomUUID(), false);

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT source_seq
                                        FROM kernel.event_outbox
                                        """,
                                Long.class))
                .isEqualTo(2L);
    }

    @Test
    void applicationRoleCannotMarkAnEventPublished() {

        publish(UUID.randomUUID(), UUID.randomUUID(), false);

        assertThatThrownBy(
                        () -> jdbc.update(
                                """
                                        UPDATE kernel.event_outbox
                                           SET published_at = now()
                                        """))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void crashBetweenPublishAndUpdateStillAppliesConsumerOnce() {

        publish(UUID.randomUUID(), UUID.randomUUID(), false);

        AtomicInteger applied = new AtomicInteger();

        AtomicBoolean crashAfterDelivery = new AtomicBoolean(true);

        BrokerAdapter broker = message -> {
            TransactionTemplate consumerTransaction = new TransactionTemplate(transactionManager);

            consumerTransaction.executeWithoutResult(status -> {
                // A consumer claims in the scope of the event's owner (V0032).
                jdbc.queryForList(
                        "SELECT set_config('app.scope_entity_id', ?, true),"
                                + " set_config('app.scope_class', 'OWN', true)",
                        message.ownerEntityId().toString());
                inbox.applyOnce("test.consumer", message.eventId(), applied::incrementAndGet);
            });

            if (crashAfterDelivery.getAndSet(false)) {
                throw new IllegalStateException("simulated crash after publish");
            }
        };

        OutboxRelay relayOne = relay(broker);

        try {
            assertThatThrownBy(relayOne::relayOnce).isInstanceOf(IllegalStateException.class);
        } finally {
            relayOne.close();
        }

        assertThat(applied.get()).isEqualTo(1);

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT published_at IS NULL
                                        FROM kernel.event_outbox
                                        """,
                                Boolean.class))
                .isTrue();

        OutboxRelay relayTwo = relay(broker);

        try {
            assertThat(relayTwo.relayOnce()).isEqualTo(1);
        } finally {
            relayTwo.close();
        }

        assertThat(applied.get()).isEqualTo(1);

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT count(*)
                                        FROM kernel.event_inbox
                                        WHERE consumer = 'test.consumer'
                                        """,
                                Integer.class))
                .isEqualTo(1);

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT published_at IS NOT NULL
                                        FROM kernel.event_outbox
                                        """,
                                Boolean.class))
                .isTrue();

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT pg_has_role(
                                            'coop_relay',
                                            'app_relay',
                                            'member'
                                        )
                                        """,
                                Boolean.class))
                .isTrue();

        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                        SELECT pg_has_role(
                                            'coop_relay',
                                            'app_rw',
                                            'member'
                                        )
                                        """,
                                Boolean.class))
                .isFalse();
    }

    private OutboxRelay relay(BrokerAdapter broker) {

        return new OutboxRelay(
                OutboxRelay.openDataSource(POSTGRES.getJdbcUrl(), RELAY_USER, RELAY_PASSWORD, 2), 500, broker);
    }

    private void publish(UUID greetingId, UUID correlationId, boolean rollback) {

        UUID entityId = UUID.randomUUID();

        UUID userId = UUID.randomUUID();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            jdbc.queryForList(
                    """
                            SELECT
                                set_config(
                                    'app.user_id',
                                    ?,
                                    true
                                ),
                                set_config(
                                    'app.correlation_id',
                                    ?,
                                    true
                                ),
                                set_config(
                                    'app.scope_entity_id',
                                    ?,
                                    true
                                ),
                                set_config(
                                    'app.scope_location_id',
                                    '',
                                    true
                                ),
                                set_config(
                                    'app.scope_class',
                                    'OWN',
                                    true
                                ),
                                set_config(
                                    'app.granted_entities',
                                    '{}',
                                    true
                                )
                            """,
                    userId.toString(),
                    correlationId.toString(),
                    entityId.toString());

            events.publish(new GreetingRegistered(greetingId, entityId));

            if (rollback) {
                status.setRollbackOnly();
            }
        });
    }
}
