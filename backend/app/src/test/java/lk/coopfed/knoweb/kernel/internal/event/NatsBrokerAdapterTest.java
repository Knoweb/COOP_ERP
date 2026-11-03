package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class NatsBrokerAdapterTest {

    @Test
    void stubConformsToTheBrokerBoundaryWithoutBecomingTheDefaultBroker() {
        BrokerAdapter adapter = new NatsBrokerAdapter();
        OutboxMessage event = message();

        assertThat(NatsBrokerAdapter.class.isAnnotationPresent(Component.class)).isFalse();
        assertThatThrownBy(() -> adapter.publish(event))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(NatsBrokerAdapter.NOT_IMPLEMENTED);
        assertThatThrownBy(() -> adapter.publishToConsumer("m5.receipts", event, 2))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(NatsBrokerAdapter.NOT_IMPLEMENTED);
        assertThatThrownBy(() -> adapter.deadLetter("domain.dlq", "m5.receipts", event, 3, "test"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(NatsBrokerAdapter.NOT_IMPLEMENTED);
    }

    private static OutboxMessage message() {
        UUID aggregateId = UUID.randomUUID();
        return new OutboxMessage(
                UUID.randomUUID(),
                "hello.greeting.registered.v1",
                Instant.now(),
                "central",
                42L,
                UUID.randomUUID(),
                null,
                "greeting",
                aggregateId,
                UUID.randomUUID(),
                null,
                null,
                null,
                "{\"greetingId\":\"" + aggregateId + "\"}");
    }
}
