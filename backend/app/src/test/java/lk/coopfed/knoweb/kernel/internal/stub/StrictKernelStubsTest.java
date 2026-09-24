package lk.coopfed.knoweb.kernel.internal.stub;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The remaining 17A event stub refuses what the real 19A event service will refuse,
 * so a developer meets the mistake on the first run.
 */
class StrictKernelStubsTest {

    private final LoggingEventPublisher events = new LoggingEventPublisher();

    record GoodEvent(UUID id) implements DomainEvent {
        public static final String TYPE = "hello.greeting.registered.v1";
    }

    record EventWithoutType(UUID id) implements DomainEvent {}

    record EventWithUnversionedType(UUID id) implements DomainEvent {
        public static final String TYPE = "hello.greeting.registered";
    }

    @BeforeEach
    void pretendATransactionIsOpen() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void closeIt() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void aCorrectEventIsAccepted() {
        assertThatCode(() -> events.publish(new GoodEvent(Ids.next()))).doesNotThrowAnyException();
    }

    @Test
    void publishingOutsideATransactionIsRefused() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> events.publish(new GoodEvent(Ids.next())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
    }

    @Test
    void anEventWithoutAVersionedTypeConstantIsRefused() {
        assertThatThrownBy(() -> events.publish(new EventWithoutType(Ids.next())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must declare");

        assertThatThrownBy(() -> events.publish(new EventWithUnversionedType(Ids.next())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must look like");

        assertThatThrownBy(() -> events.publish(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
