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
    void entityScopedNonOwnClassCanStillWriteItsEvent() {

        UUID entity = UUID.randomUUID();

        publish(entity, "PARTY", new SafeEvent(UUID.randomUUID(), entity, "ACTIVE"));

        assertThat(outboxCount()).isEqualTo(1);
    }

    private void publish(UUID entity, String policyClass, DomainEvent event) {

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

            events.publish(event);
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

    private record SafeEvent(UUID thingId, UUID ownerEntityId, String status) implements DomainEvent {

        public static final String TYPE = "test.thing.safe.v1";
    }
}
