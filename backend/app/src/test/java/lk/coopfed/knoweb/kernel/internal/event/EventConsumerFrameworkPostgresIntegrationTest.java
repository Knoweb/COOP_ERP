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

        superuserJdbc().update("DELETE FROM kernel.document WHERE owner_entity_id = ?", OWNER);

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
    void aTypeNobodyConsumesIsPoisonNotARetry() throws Exception {

        EventConsumerDispatcher dispatcher = dispatcher(new TestProjection(), new CapturingBroker());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dispatcher.deliver("nobody", message(1, UUID.randomUUID()), 1))
                .isInstanceOf(PoisonMessageException.class);
    }

    @Test
    void anEnvelopeWithoutAnOwnerEntityIsPoisonNotARetry() throws Exception {

        TestProjection projection = new TestProjection();

        EventConsumerDispatcher dispatcher = dispatcher(projection, new CapturingBroker());

        OutboxMessage good = message(1, UUID.randomUUID());

        OutboxMessage ownerless = new OutboxMessage(
                good.eventId(),
                good.eventType(),
                good.occurredAt(),
                good.source(),
                good.sourceSeq(),
                null,
                null,
                good.aggregateType(),
                good.aggregateId(),
                good.correlationId(),
                null,
                null,
                null,
                good.payload());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dispatcher.deliver("test.projection", ownerless, 1))
                .isInstanceOf(PoisonMessageException.class);

        assertThat(projection.applied.get()).isZero();
    }

    @Test
    void aClaimIsVisibleToTheScopeOfItsEventOnly() throws Exception {

        EventConsumerDispatcher dispatcher = dispatcher(new TestProjection(), new CapturingBroker());

        OutboxMessage message = message(1, UUID.randomUUID());

        dispatcher.deliver("test.projection", message, 1);

        assertThat(inboxRowsVisibleTo(message.ownerEntityId())).isEqualTo(1);

        assertThat(inboxRowsVisibleTo(UUID.randomUUID())).isZero();
    }

    private int auditRows(String eventTypeCode, UUID subjectId) {
        return superuserJdbc()
                .queryForObject(
                        "SELECT count(*) FROM kernel.audit_event WHERE event_type_code = ? AND subject_id = ?",
                        Integer.class,
                        eventTypeCode,
                        subjectId);
    }

    private int inboxRowsVisibleTo(UUID entity) {
        return inboxRowsVisibleTo(entity, "test.projection");
    }

    private int inboxRowsVisibleTo(UUID entity, String consumer) {

        Integer count = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    jdbc.queryForList(
                            "SELECT set_config('app.scope_entity_id', ?, true),"
                                    + " set_config('app.scope_class', 'OWN', true)",
                            entity.toString());
                    return jdbc.queryForObject(
                            "SELECT count(*) FROM kernel.event_inbox WHERE consumer = ?", Integer.class, consumer);
                });

        return count == null ? 0 : count;
    }

    // ---- counterparty delivery (wave 2, CR-19A-13) ------------------------------------------------

    private static final UUID OWNER = UUID.fromString("0190c300-0000-7000-8000-000000000001");
    private static final UUID COUNTERPARTY = UUID.fromString("0190c300-0000-7000-8000-000000000002");
    private static final UUID STRANGER = UUID.fromString("0190c300-0000-7000-8000-000000000003");

    /** A document of OWNER's with COUNTERPARTY as its other party, as M4 issues an invoice. */
    private UUID twoPartyDocument() {
        UUID documentId = UUID.randomUUID();
        superuserJdbc()
                .update(
                        """
                        INSERT INTO kernel.document (document_id, doc_type_code, owner_entity_id, counterparty_entity_id, status)
                        VALUES (?, 'INV', ?, ?, 'DRAFT')
                        """,
                        documentId,
                        OWNER,
                        COUNTERPARTY);
        return documentId;
    }

    private OutboxMessage twoPartyMessage(UUID documentId, UUID counterparty, String source) throws Exception {

        com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
        payload.put("documentId", documentId.toString());
        if (counterparty != null) {
            payload.put("counterpartyEntityId", counterparty.toString());
        }

        return new OutboxMessage(
                UUID.randomUUID(),
                TestCounterpartyProjection.TYPE,
                Instant.now(),
                source,
                1,
                OWNER,
                null,
                "invoice",
                documentId,
                UUID.randomUUID(),
                null,
                null,
                null,
                mapper.writeValueAsString(payload));
    }

    private EventConsumerDispatcher counterpartyDispatcher(TestCounterpartyProjection projection) {

        EventConsumerRegistry registry = new EventConsumerRegistry();

        registry.register(projection);

        return new EventConsumerDispatcher(
                registry, inbox, new DeadLetter(new CapturingBroker()), audit, mapper, jdbc, transactionManager);
    }

    @Test
    void aCounterpartyConsumerRunsInTheCounterpartysScopeWithItsOwnInboxRow() throws Exception {

        TestCounterpartyProjection projection = new TestCounterpartyProjection();

        EventConsumerDispatcher dispatcher = counterpartyDispatcher(projection);

        OutboxMessage message = twoPartyMessage(twoPartyDocument(), COUNTERPARTY, "central");

        assertThat(dispatcher.deliver(TestCounterpartyProjection.CONSUMER, message, 1))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.APPLIED);

        assertThat(projection.applied.get()).isEqualTo(1);
        // The scope the consumer saw: OWN, entity-wide, the counterparty's, no device.
        assertThat(projection.lastScope.entityId()).isEqualTo(COUNTERPARTY);
        assertThat(projection.lastScope.locationId()).isNull();
        assertThat(projection.lastScope.deviceId()).isNull();
        assertThat(projection.lastScope.policyClass()).isEqualTo(lk.coopfed.knoweb.kernel.api.PolicyClass.OWN);
        // The database session agreed with it.
        assertThat(projection.lastSessionEntity).isEqualTo(COUNTERPARTY.toString());

        // Its own inbox row, in the counterparty's scope: the owner's consumers see nothing of it.
        assertThat(inboxRowsVisibleTo(COUNTERPARTY, TestCounterpartyProjection.CONSUMER))
                .isEqualTo(1);
        assertThat(inboxRowsVisibleTo(OWNER, TestCounterpartyProjection.CONSUMER))
                .isZero();

        // Redelivered: a duplicate, not applied twice.
        assertThat(dispatcher.deliver(TestCounterpartyProjection.CONSUMER, message, 1))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.DUPLICATE);
        assertThat(projection.applied.get()).isEqualTo(1);
    }

    @Test
    void aDeviceEventIsNeverDeliveredToACounterpartyConsumer() throws Exception {

        TestCounterpartyProjection projection = new TestCounterpartyProjection();

        EventConsumerDispatcher dispatcher = counterpartyDispatcher(projection);

        OutboxMessage fromATill = twoPartyMessage(
                twoPartyDocument(), COUNTERPARTY, UUID.randomUUID().toString());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dispatcher.deliver(TestCounterpartyProjection.CONSUMER, fromATill, 1))
                .isInstanceOf(PoisonMessageException.class)
                .hasMessageContaining("device");

        assertThat(projection.applied.get()).isZero();
        assertThat(inboxRowsVisibleTo(COUNTERPARTY, TestCounterpartyProjection.CONSUMER))
                .isZero();
    }

    @Test
    void aCounterpartyThatIsNotTheDocumentsIsRefusedAndNothingIsWritten() throws Exception {

        TestCounterpartyProjection projection = new TestCounterpartyProjection();

        EventConsumerDispatcher dispatcher = counterpartyDispatcher(projection);

        UUID documentId = twoPartyDocument();

        // A payload naming a stranger: the document says COUNTERPARTY, so the stranger's books
        // are never entered. Poison, not a retry: the third attempt would say the same.
        OutboxMessage stranger = twoPartyMessage(documentId, STRANGER, "central");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dispatcher.deliver(TestCounterpartyProjection.CONSUMER, stranger, 3))
                .isInstanceOf(PoisonMessageException.class)
                .hasMessageContaining(STRANGER.toString());
        // The refusal leaves a record in the owner's scope (M7M8M9-09), naming identifiers only.
        assertThat(superuserJdbc()
                        .queryForList(
                                """
                                SELECT owner_entity_id, subject_table
                                  FROM kernel.audit_event
                                 WHERE event_type_code = 'EVENT_COUNTERPARTY_REFUSED'
                                   AND subject_id = ?
                                """,
                                stranger.eventId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("owner_entity_id")).isEqualTo(OWNER);
                    assertThat(row.get("subject_table")).isEqualTo("event");
                });

        // The owner naming itself.
        OutboxMessage self = twoPartyMessage(documentId, OWNER, "central");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dispatcher.deliver(TestCounterpartyProjection.CONSUMER, self, 1))
                .isInstanceOf(PoisonMessageException.class);

        // A document that does not exist, and a payload with no document at all.
        OutboxMessage noSuchDocument = twoPartyMessage(UUID.randomUUID(), COUNTERPARTY, "central");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dispatcher.deliver(TestCounterpartyProjection.CONSUMER, noSuchDocument, 1))
                .isInstanceOf(PoisonMessageException.class);
        com.fasterxml.jackson.databind.node.ObjectNode noDocument = mapper.createObjectNode();
        noDocument.put("counterpartyEntityId", COUNTERPARTY.toString());
        OutboxMessage withoutDocument = new OutboxMessage(
                UUID.randomUUID(),
                TestCounterpartyProjection.TYPE,
                Instant.now(),
                "central",
                1,
                OWNER,
                null,
                "invoice",
                null,
                UUID.randomUUID(),
                null,
                null,
                null,
                mapper.writeValueAsString(noDocument));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dispatcher.deliver(TestCounterpartyProjection.CONSUMER, withoutDocument, 1))
                .isInstanceOf(PoisonMessageException.class);

        // The second check refused the stranger, the missing document and the absent document:
        // each is recorded once; the owner naming itself fails the first check (no record).
        for (OutboxMessage refused : java.util.List.of(stranger, noSuchDocument, withoutDocument)) {
            assertThat(auditRows("EVENT_COUNTERPARTY_REFUSED", refused.eventId()))
                    .as("refusal recorded for %s", refused.eventId())
                    .isEqualTo(1);
        }
        assertThat(auditRows("EVENT_COUNTERPARTY_REFUSED", self.eventId())).isZero();

        assertThat(projection.applied.get()).isZero();
        for (UUID entity : java.util.List.of(OWNER, COUNTERPARTY, STRANGER)) {
            assertThat(inboxRowsVisibleTo(entity, TestCounterpartyProjection.CONSUMER))
                    .isZero();
        }
        assertThat(superuserJdbc()
                        .queryForObject(
                                "SELECT count(*) FROM kernel.event_inbox WHERE consumer = ?",
                                Integer.class,
                                TestCounterpartyProjection.CONSUMER))
                .isZero();
    }

    @Test
    void anEventWithoutACounterpartyIsPassedOverByACounterpartyConsumer() throws Exception {

        TestCounterpartyProjection projection = new TestCounterpartyProjection();

        EventConsumerDispatcher dispatcher = counterpartyDispatcher(projection);

        // Published before the field existed, or a document with one party: nothing to deliver,
        // and not an error either (a replay of old events must not dead-letter them all).
        OutboxMessage onePartyEvent = twoPartyMessage(twoPartyDocument(), null, "central");

        assertThat(dispatcher.deliver(TestCounterpartyProjection.CONSUMER, onePartyEvent, 1))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.APPLIED);

        assertThat(projection.applied.get()).isZero();
    }

    @Test
    void aCounterpartyConsumerOfEveryTypeIsRefusedAtRegistration() {

        EventConsumerRegistry registry = new EventConsumerRegistry();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.register(new Object() {
                    @EventConsumer(
                            types = "*",
                            consumer = "test.counterparty.star",
                            party = EventConsumer.Party.COUNTERPARTY)
                    public void on(com.fasterxml.jackson.databind.JsonNode event, ScopeContext scope) {}
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COUNTERPARTY");
    }

    /** A consumer registered for the counterparty of a two-party event, recording the scope it saw. */
    final class TestCounterpartyProjection {

        static final String TYPE = "test.two_party.v1";
        static final String CONSUMER = "test.counterparty";

        final AtomicInteger applied = new AtomicInteger();

        volatile ScopeContext lastScope;
        volatile String lastSessionEntity;

        @EventConsumer(
                types = {TYPE},
                consumer = CONSUMER,
                party = EventConsumer.Party.COUNTERPARTY)
        public void onTwoParty(com.fasterxml.jackson.databind.JsonNode event, ScopeContext scope) {

            lastScope = scope;
            lastSessionEntity =
                    jdbc.queryForObject("SELECT current_setting('app.scope_entity_id', true)", String.class);
            applied.incrementAndGet();
        }
    }

    @Test
    void replayPagesThroughTheArchive() throws Exception {

        for (long seq = 1; seq <= 5; seq++) {
            insertArchive(message(seq, UUID.randomUUID()));
        }

        TestProjection projection = new TestProjection();

        CapturingBroker broker = new CapturingBroker();

        broker.dispatcher = dispatcher(projection, broker);

        HikariDataSource relayDataSource =
                OutboxRelay.openDataSource(POSTGRES.getJdbcUrl(), "coop_relay", "coop_relay", 2);

        try {

            EventConsumerRegistry registry = new EventConsumerRegistry();
            registry.register(projection);

            Replayer replayer = new Replayer(relayDataSource, broker, registry, true);
            replayer.pageSize = 2;

            assertThat(replayer.replay("test.projection", 2)).isEqualTo(4);

        } finally {
            relayDataSource.close();
        }

        assertThat(projection.applied.get()).isEqualTo(4);
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
