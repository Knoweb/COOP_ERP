package lk.coopfed.knoweb.m9integration.internal.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.InitiatePayment;
import lk.coopfed.knoweb.m9integration.api.PaymentCodPending;
import lk.coopfed.knoweb.m9integration.api.PaymentInitiated;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.LankaQrPaymentProviderAdapter;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.MockPaymentProviderAdapter;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentAdapterRegistry;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class InitiatePaymentHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Instant fixedInstant = Instant.parse("2026-11-03T10:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();

    private InitiatePaymentHandler handler;

    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ENTITY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");

    private static ScopeContext ownScope() {
        Scope s = new Scope(ENTITY_ID, null);
        return new ScopeContext(
                USER_ID, null, ENTITY_ID, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    @BeforeEach
    void setUp() {
        PaymentAdapterRegistry registry = new PaymentAdapterRegistry(
                List.of(new MockPaymentProviderAdapter(), new LankaQrPaymentProviderAdapter()));
        handler = new InitiatePaymentHandler(jdbc, audit, events, clock, registry, json);
    }

    @Test
    void rejectsInvalidAmount() {
        InitiatePayment cmd =
                new InitiatePayment(null, "ORD-1", "CUST-1", BigDecimal.ZERO, "ONLINE", "mock", null, null, Map.of());
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.payment.amount_positive");
    }

    @Test
    void rejectsMissingOrderReference() {
        InitiatePayment cmd = new InitiatePayment(
                null, "", "CUST-1", new BigDecimal("100.00"), "ONLINE", "mock", null, null, Map.of());
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.payment.order_reference_required");
    }

    @Test
    void rejectsInvalidPaymentMethod() {
        InitiatePayment cmd = new InitiatePayment(
                null, "ORD-1", "CUST-1", new BigDecimal("100.00"), "BITCOIN", "mock", null, null, Map.of());
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.payment.payment_method_invalid");
    }

    @Test
    void initiatesOnlinePaymentSuccessfully() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY_ID), anyString()))
                .thenReturn(List.of());

        InitiatePayment cmd = new InitiatePayment(
                null,
                "ORD-100",
                "CUST-100",
                new BigDecimal("5000.00"),
                "ONLINE",
                "mock",
                "Test payment",
                "DEDUP-100",
                Map.of());

        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result).isNotNull();
        assertThat(result.orderReference()).isEqualTo("ORD-100");
        assertThat(result.amount()).isEqualTo(new BigDecimal("5000.00"));
        assertThat(result.paymentMethod()).isEqualTo("ONLINE");
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.provider()).isEqualTo("mock");

        // Verify Audit (zero PII)
        ArgumentCaptor<Subject> subjectCaptor = ArgumentCaptor.forClass(Subject.class);
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(InitiatePaymentHandler.AUDIT_PAYMENT_INITIATED),
                        subjectCaptor.capture(),
                        any(),
                        auditCaptor.capture(),
                        any());

        assertThat(subjectCaptor.getValue().id()).isNotNull();
        assertThat(subjectCaptor.getValue().type()).isEqualTo("payment_intent");
        assertThat(auditCaptor.getValue())
                .containsEntry("order_reference", "ORD-100")
                .containsEntry("amount", new BigDecimal("5000.00"))
                .containsEntry("payment_method", "ONLINE")
                .doesNotContainKey("customerPhone")
                .doesNotContainKey("customerNic");

        // Verify Domain Event
        ArgumentCaptor<PaymentInitiated> eventCaptor = ArgumentCaptor.forClass(PaymentInitiated.class);
        verify(events).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().orderReference()).isEqualTo("ORD-100");
        assertThat(eventCaptor.getValue().amount()).isEqualTo(new BigDecimal("5000.00"));
    }

    @Test
    void initiatesLankaQrPaymentWithDynamicQr() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY_ID), anyString()))
                .thenReturn(List.of());

        InitiatePayment cmd = new InitiatePayment(
                null,
                "ORD-LQR",
                "CUST-200",
                new BigDecimal("1250.00"),
                "LANKA_QR",
                "lankaqr",
                "LankaQR bill",
                "DEDUP-LQR",
                Map.of());

        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result).isNotNull();
        assertThat(result.paymentMethod()).isEqualTo("LANKA_QR");
        assertThat(result.qrPayload()).isNotNull();
        assertThat(result.qrPayload()).startsWith("000201010212");
        assertThat(result.qrPayload()).contains("144");
        assertThat(result.qrPayload()).contains("1250.00");
    }

    @Test
    void initiatesCodPaymentAsCodPending() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY_ID), anyString()))
                .thenReturn(List.of());

        InitiatePayment cmd = new InitiatePayment(
                null,
                "ORD-COD",
                "CUST-300",
                new BigDecimal("3200.00"),
                "COD",
                "mock",
                "COD delivery",
                "DEDUP-COD",
                Map.of());

        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result).isNotNull();
        assertThat(result.paymentMethod()).isEqualTo("COD");
        assertThat(result.status()).isEqualTo("COD_PENDING");

        // Verify PaymentCodPending event published
        ArgumentCaptor<PaymentCodPending> eventCaptor = ArgumentCaptor.forClass(PaymentCodPending.class);
        verify(events).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().orderReference()).isEqualTo("ORD-COD");
        assertThat(eventCaptor.getValue().amount()).isEqualTo(new BigDecimal("3200.00"));
    }
}
