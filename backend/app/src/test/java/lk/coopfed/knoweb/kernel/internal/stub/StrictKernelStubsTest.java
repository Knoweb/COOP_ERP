package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 17A audit and event stubs refuse what the real tables of 19A will refuse, so a
 * developer meets the mistake on the first run. One failing case per refusal.
 */
class StrictKernelStubsTest {

    private final LoggingAuditFacade audit = new LoggingAuditFacade();
    private final LoggingEventPublisher events = new LoggingEventPublisher();

    private final ScopeContext scope = ScopeContext.dev(Ids.next(), Ids.next(), null);
    private final Subject subject = Subject.of("greeting", Ids.next());

    record GoodEvent(UUID id) implements DomainEvent {
        public static final String TYPE = "hello.greeting.registered.v1";
    }

    record EventWithoutType(UUID id) implements DomainEvent {
    }

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
    void aCorrectAuditRecordAndEventAreAccepted() {
        assertThatCode(() -> audit.record("HELLO_GREETING_REGISTERED", subject, null, "after", scope))
                .doesNotThrowAnyException();
        assertThatCode(() -> events.publish(new GoodEvent(Ids.next())))
                .doesNotThrowAnyException();
    }

    @Test
    void anAuditTypeThatIsNotACatalogueCodeIsRefused() {
        for (String bad : new String[] {null, "", "greeting registered", "hello.greeting.registered", "X".repeat(41)}) {
            assertThatThrownBy(() -> audit.record(bad, subject, null, "after", scope))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("catalogue code");
        }
    }

    @Test
    void anAuditRecordWithoutASubjectIsRefused() {
        assertThatThrownBy(() -> audit.record("HELLO_GREETING_REGISTERED", null, null, "after", scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
        assertThatThrownBy(() -> audit.record("HELLO_GREETING_REGISTERED", Subject.of("greeting", null), null, "after", scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void anAuditRecordWithoutAnActiveScopeIsRefused() {
        ScopeContext noActiveScope = new ScopeContext(
                Ids.next(), null, null, List.of(), null, null, null, null, Locale.ENGLISH, null);

        assertThatThrownBy(() -> audit.record("HELLO_GREETING_REGISTERED", subject, null, "after", noActiveScope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active entity");
        assertThatThrownBy(() -> audit.record("HELLO_GREETING_REGISTERED", subject, null, "after", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditingOutsideATransactionIsRefused() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> audit.record("HELLO_GREETING_REGISTERED", subject, null, "after", scope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
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
        assertThatThrownBy(() -> events.publish(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
