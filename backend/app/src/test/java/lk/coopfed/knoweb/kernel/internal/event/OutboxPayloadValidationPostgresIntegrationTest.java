package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxPayloadValidationPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EventPublisher events;

    @BeforeEach
    void cleanOutbox() {

        superuserJdbc().execute("TRUNCATE TABLE kernel.event_outbox");
    }

    @Test
    void payloadLargerThanEightKilobytesIsRefused() {

        UUID entity = UUID.randomUUID();

        assertThatThrownBy(
                        () -> publish(entity, "OWN", new OversizedEvent(UUID.randomUUID(), entity, "x".repeat(9000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 KB");

        assertThat(outboxCount()).isZero();
    }

    @Test
    void personalDataFieldNamesAreRefused() {

        UUID entity = UUID.randomUUID();

        assertThatThrownBy(() -> publish(
                        entity,
                        "OWN",
                        new PersonalDataEvent(UUID.randomUUID(), entity, "0770000000", "200000000000", "Person Name")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden field");

        assertThat(outboxCount()).isZero();
    }

    @Test
    void theWordListRefusesPersonalDataThatTheOldSubstringCheckLetThrough() {

        UUID entity = UUID.randomUUID();

        assertThatThrownBy(() -> publish(entity, "OWN", new CustomerEvent(UUID.randomUUID(), entity, "A Person")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden field customerName");

        assertThat(outboxCount()).isZero();
    }

    @Test
    void aForbiddenFieldInsideANestedObjectOrAnArrayIsRefused() {

        UUID entity = UUID.randomUUID();

        assertThatThrownBy(() -> publish(
                        entity,
                        "OWN",
                        new NestedEvent(
                                UUID.randomUUID(),
                                entity,
                                new Contact("a@b.lk"),
                                java.util.List.of(new Contact("c@d.lk")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden field email");

        assertThat(outboxCount()).isZero();
    }

    @Test
    void identifiersCodesAndCatalogueDisplayNamesArePublished() {

        UUID entity = UUID.randomUUID();

        publish(
                entity,
                "OWN",
                new CatalogueEvent(
                        UUID.randomUUID(),
                        entity,
                        UUID.randomUUID(),
                        "COL-01",
                        UUID.randomUUID(),
                        "Rice 5 kg",
                        "Kilogram",
                        "Rice",
                        "Haal",
                        "Arisi"));

        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void aFailingListenerDoesNotStopTheOthersAndOneSynchronisationServesTheTransaction() {

        UUID entity = UUID.randomUUID();
        java.util.List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();

        org.springframework.beans.factory.support.StaticListableBeanFactory listeners =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();
        listeners.addBean("failing", (PublishedEventListener) (type, payload) -> {
            throw new IllegalStateException("listener failure");
        });
        listeners.addBean("hearing", (PublishedEventListener) (type, payload) -> heard.add(type));

        OutboxWriter writer = new OutboxWriter(
                jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                listeners.getBeanProvider(PublishedEventListener.class));

        int[] synchronisations = new int[2];

        inScope(entity, "OWN", () -> {
            int before = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .size();
            writer.publish(new SafeEvent(UUID.randomUUID(), entity, "ACTIVE"));
            writer.publish(new SafeEvent(UUID.randomUUID(), entity, "ACTIVE"));
            synchronisations[0] = before;
            synchronisations[1] =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                            .size();
        });

        assertThat(synchronisations[1] - synchronisations[0]).isEqualTo(1);
        assertThat(heard).containsExactly(SafeEvent.TYPE, SafeEvent.TYPE);
        assertThat(outboxCount()).isEqualTo(2);
    }

    @Test
    void entityScopedNonOwnClassCannotWriteAnEvent() {
        // A PARTY, FEDERATION_VIEW or EXTERNAL caller has a scope entity too and writes nothing
        // through it (doc 18 section 3.7; CR-17A-3): the outbox insert policy tests the class.
        UUID entity = UUID.randomUUID();

        assertThatThrownBy(() -> publish(entity, "PARTY", new SafeEvent(UUID.randomUUID(), entity, "ACTIVE")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(outboxCount()).isZero();
    }

    private void publish(UUID entity, String policyClass, DomainEvent event) {

        inScope(entity, policyClass, () -> events.publish(event));
    }

    private void inScope(UUID entity, String policyClass, Runnable work) {

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
                                    ?,
                                    true
                                ),
                                set_config(
                                    'app.granted_entities',
                                    '{}',
                                    true
                                )
                            """,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    entity.toString(),
                    policyClass);

            work.run();
        });
    }

    private int outboxCount() {

        return superuserJdbc()
                .queryForObject(
                        """
                        SELECT count(*)
                        FROM kernel.event_outbox
                        """,
                        Integer.class);
    }

    private record OversizedEvent(UUID thingId, UUID ownerEntityId, String description) implements DomainEvent {

        public static final String TYPE = "test.thing.oversized.v1";
    }

    private record PersonalDataEvent(UUID thingId, UUID ownerEntityId, String phone, String nic, String name)
            implements DomainEvent {

        public static final String TYPE = "test.thing.personal_data.v1";
    }

    private record CustomerEvent(UUID thingId, UUID ownerEntityId, String customerName) implements DomainEvent {

        public static final String TYPE = "test.thing.customer.v1";
    }

    private record Contact(String email) {}

    private record NestedEvent(UUID thingId, UUID ownerEntityId, Contact primary, java.util.List<Contact> others)
            implements DomainEvent {

        public static final String TYPE = "test.thing.nested.v1";
    }

    private record CatalogueEvent(
            UUID thingId,
            UUID ownerEntityId,
            UUID addressId,
            String cityCode,
            UUID technicianId,
            String productName,
            String uomName,
            String nameEn,
            String nameSi,
            String nameTa)
            implements DomainEvent {

        public static final String TYPE = "test.thing.catalogue.v1";
    }

    private record SafeEvent(UUID thingId, UUID ownerEntityId, String status) implements DomainEvent {

        public static final String TYPE = "test.thing.safe.v1";
    }
}
