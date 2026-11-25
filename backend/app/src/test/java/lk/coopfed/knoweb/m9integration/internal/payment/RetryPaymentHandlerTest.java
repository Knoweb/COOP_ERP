package lk.coopfed.knoweb.m9integration.internal.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.PaymentRetried;
import lk.coopfed.knoweb.m9integration.api.RetryPayment;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.MockPaymentProviderAdapter;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentAdapterRegistry;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RetryPaymentHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Instant fixedInstant = Instant.parse("2026-11-03T13:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();

    private RetryPaymentHandler handler;

    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ENTITY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID PAYMENT_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000099");

    private static ScopeContext ownScope() {
        Scope s = new Scope(ENTITY_ID, null);
        return new ScopeContext(
                USER_ID, null, ENTITY_ID, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    @BeforeEach
    void setUp() {
        PaymentAdapterRegistry registry = new PaymentAdapterRegistry(List.of(new MockPaymentProviderAdapter()));
        handler = new RetryPaymentHandler(jdbc, audit, events, clock, registry, json);
    }

    @Test
    void rejectsNonExistentPayment() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of());

        RetryPayment cmd = new RetryPayment(PAYMENT_ID, "mock");
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.payment.not_found");
    }

    @Test
    void retryingAlreadyPaidPaymentReturnsIdempotentlyWithoutReexecuting() {
        PaymentIntentView paid = new PaymentIntentView(
                PAYMENT_ID,
                ENTITY_ID,
                "ORD-1",
                "CUST-1",
                new BigDecimal("1000.00"),
                "LKR",
                "ONLINE",
                "PAID",
                "mock",
                "TXN-PAID",
                null,
                "Desc",
                null,
                "DEDUP-1",
                Map.of(),
                fixedInstant.minusSeconds(100),
                fixedInstant.minusSeconds(10),
                fixedInstant.minusSeconds(100),
                fixedInstant.minusSeconds(10));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of(paid));

        RetryPayment cmd = new RetryPayment(PAYMENT_ID, "mock");
        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result.status()).isEqualTo("PAID");
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void retriesFailedPaymentSuccessfully() {
        PaymentIntentView failed = new PaymentIntentView(
                PAYMENT_ID,
                ENTITY_ID,
                "ORD-1",
                "CUST-1",
                new BigDecimal("1000.00"),
                "LKR",
                "ONLINE",
                "FAILED",
                "mock",
                "TXN-OLD",
                null,
                "Desc",
                "Timeout",
                "DEDUP-1",
                Map.of(),
                fixedInstant.minusSeconds(100),
                null,
                fixedInstant.minusSeconds(100),
                fixedInstant.minusSeconds(10));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of(failed));

        RetryPayment cmd = new RetryPayment(PAYMENT_ID, "mock");
        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.metadata()).containsKey("retryCount");
        assertThat(result.metadata().get("retryCount")).isEqualTo(1);

        // Verify audit
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(RetryPaymentHandler.AUDIT_PAYMENT_RETRIED), any(), any(), auditCaptor.capture(), any());
        assertThat(auditCaptor.getValue()).containsEntry("retry_count", 1);
        verify(events).publish(any(PaymentRetried.class));
    }
}
