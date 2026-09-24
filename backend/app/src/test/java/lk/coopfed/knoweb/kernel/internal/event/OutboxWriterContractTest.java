package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxWriterContractTest {

    private final OutboxWriter writer = new OutboxWriter(mock(JdbcTemplate.class), new ObjectMapper());

    @Test
    void nullEventIsRefused() {

        assertThatThrownBy(() -> writer.publish(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null event");
    }

    @Test
    void eventWithoutATypeConstantIsRefused() {

        assertThatThrownBy(() -> writer.publish(new MissingTypeEvent(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must declare");
    }

    @Test
    void unversionedEventTypeIsRefused() {

        assertThatThrownBy(() -> writer.publish(new InvalidTypeEvent(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must look like");
    }

    @Test
    void publishingOutsideTheCallerTransactionIsRefused() {

        assertThatThrownBy(() -> writer.publish(new ValidEvent(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
    }

    private record MissingTypeEvent(UUID thingId) implements DomainEvent {}

    private record InvalidTypeEvent(UUID thingId) implements DomainEvent {

        public static final String TYPE = "not-versioned";
    }

    private record ValidEvent(UUID thingId) implements DomainEvent {

        public static final String TYPE = "test.thing.created.v1";
    }
}
