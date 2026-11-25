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
import lk.coopfed.knoweb.m9integration.api.ConfirmPayment;
import lk.coopfed.knoweb.m9integration.api.PaymentFailed;
import lk.coopfed.knoweb.m9integration.api.PaymentSettled;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ConfirmPaymentHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Instant fixedInstant = Instant.parse("2026-11-03T11:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();

    private ConfirmPaymentHandler handler;

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
        handler = new ConfirmPaymentHandler(jdbc, audit, events, clock, json);
    }

    @Test
    void rejectsNonExistentPayment() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of());

        ConfirmPayment cmd = new ConfirmPayment(PAYMENT_ID, "TXN-1", "PAID", null, Map.of());
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.payment.not_found");
    }

    @Test
    void settlesPendingPaymentSuccessfully() {
        PaymentIntentView existing = new PaymentIntentView(
                PAYMENT_ID,
                ENTITY_ID,
                "ORD-1",
                "CUST-1",
                new BigDecimal("1500.00"),
                "LKR",
                "ONLINE",
                "PENDING",
                "mock",
                "INIT-REF",
                null,
                "Desc",
                null,
                "DEDUP-1",
                Map.of(),
                fixedInstant.minusSeconds(60),
                null,
                fixedInstant.minusSeconds(60),
                fixedInstant.minusSeconds(60));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of(existing));

        ConfirmPayment cmd = new ConfirmPayment(PAYMENT_ID, "FINAL-TXN-123", "PAID", null, Map.of());
        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result.status()).isEqualTo("PAID");
        assertThat(result.providerRef()).isEqualTo("FINAL-TXN-123");
        assertThat(result.settledAt()).isEqualTo(fixedInstant);

        // Verify audit
        verify(audit).record(eq(ConfirmPaymentHandler.AUDIT_PAYMENT_SETTLED), any(), any(), any(), any());

        // Verify domain event
        ArgumentCaptor<PaymentSettled> captor = ArgumentCaptor.forClass(PaymentSettled.class);
        verify(events).publish(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(captor.getValue().amount()).isEqualTo(new BigDecimal("1500.00"));
    }

    @Test
    void idempotentOnAlreadyPaidPaymentDoesNotDoubleSettle() {
        PaymentIntentView alreadyPaid = new PaymentIntentView(
                PAYMENT_ID,
                ENTITY_ID,
                "ORD-1",
                "CUST-1",
                new BigDecimal("1500.00"),
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
                fixedInstant.minusSeconds(120),
                fixedInstant.minusSeconds(60),
                fixedInstant.minusSeconds(120),
                fixedInstant.minusSeconds(60));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of(alreadyPaid));

        ConfirmPayment cmd = new ConfirmPayment(PAYMENT_ID, "TXN-PAID-DUP", "PAID", null, Map.of());
        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result.status()).isEqualTo("PAID");
        // Verify no second audit or domain event published
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void recordsPaymentFailure() {
        PaymentIntentView existing = new PaymentIntentView(
                PAYMENT_ID,
                ENTITY_ID,
                "ORD-1",
                "CUST-1",
                new BigDecimal("1500.00"),
                "LKR",
                "ONLINE",
                "PENDING",
                "mock",
                "INIT-REF",
                null,
                "Desc",
                null,
                "DEDUP-1",
                Map.of(),
                fixedInstant.minusSeconds(60),
                null,
                fixedInstant.minusSeconds(60),
                fixedInstant.minusSeconds(60));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of(existing));

        ConfirmPayment cmd =
                new ConfirmPayment(PAYMENT_ID, "FAIL-TXN", "FAILED", "Insufficient card balance", Map.of());
        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).isEqualTo("Insufficient card balance");

        // Verify failure audit and event
        verify(audit).record(eq(ConfirmPaymentHandler.AUDIT_PAYMENT_FAILED), any(), any(), any(), any());
        verify(events).publish(any(PaymentFailed.class));
    }
}
