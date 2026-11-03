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
import lk.coopfed.knoweb.m9integration.api.ConfirmCodSettlement;
import lk.coopfed.knoweb.m9integration.api.PaymentSettled;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ConfirmCodSettlementHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Instant fixedInstant = Instant.parse("2026-11-03T12:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();

    private ConfirmCodSettlementHandler handler;

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
        handler = new ConfirmCodSettlementHandler(jdbc, audit, events, clock, json);
    }

    @Test
    void rejectsNonCodPayment() {
        PaymentIntentView onlineIntent = new PaymentIntentView(
                PAYMENT_ID,
                ENTITY_ID,
                "ORD-1",
                "CUST-1",
                new BigDecimal("1000.00"),
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

        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of(onlineIntent));

        ConfirmCodSettlement cmd = new ConfirmCodSettlement(PAYMENT_ID, "RCP-001", Map.of());
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.payment.not_cod");
    }

    @Test
    void confirmsCodCollectionAndSettles() {
        PaymentIntentView codIntent = new PaymentIntentView(
                PAYMENT_ID,
                ENTITY_ID,
                "ORD-COD",
                "CUST-1",
                new BigDecimal("4500.00"),
                "LKR",
                "COD",
                "COD_PENDING",
                "mock",
                "COD-REF",
                null,
                "COD delivery",
                null,
                "DEDUP-COD",
                Map.of(),
                fixedInstant.minusSeconds(60),
                null,
                fixedInstant.minusSeconds(60),
                fixedInstant.minusSeconds(60));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of(codIntent));

        ConfirmCodSettlement cmd =
                new ConfirmCodSettlement(PAYMENT_ID, "CASH-RECEIPT-8899", Map.of("agent", "driver-01"));
        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result.status()).isEqualTo("PAID");
        assertThat(result.providerRef()).isEqualTo("CASH-RECEIPT-8899");
        assertThat(result.settledAt()).isEqualTo(fixedInstant);

        // Verify audit
        verify(audit).record(eq(ConfirmCodSettlementHandler.AUDIT_COD_COLLECTED), any(), any(), any(), any());

        // Verify domain event
        ArgumentCaptor<PaymentSettled> captor = ArgumentCaptor.forClass(PaymentSettled.class);
        verify(events).publish(captor.capture());
        assertThat(captor.getValue().paymentMethod()).isEqualTo("COD");
        assertThat(captor.getValue().amount()).isEqualTo(new BigDecimal("4500.00"));
    }

    @Test
    void idempotentCodSettlementDoesNotDoubleSettle() {
        PaymentIntentView alreadySettled = new PaymentIntentView(
                PAYMENT_ID,
                ENTITY_ID,
                "ORD-COD",
                "CUST-1",
                new BigDecimal("4500.00"),
                "LKR",
                "COD",
                "PAID",
                "mock",
                "CASH-RECEIPT-8899",
                null,
                "COD delivery",
                null,
                "DEDUP-COD",
                Map.of(),
                fixedInstant.minusSeconds(120),
                fixedInstant.minusSeconds(30),
                fixedInstant.minusSeconds(120),
                fixedInstant.minusSeconds(30));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(PAYMENT_ID))).thenReturn(List.of(alreadySettled));

        ConfirmCodSettlement cmd = new ConfirmCodSettlement(PAYMENT_ID, "CASH-RECEIPT-8899", Map.of());
        PaymentIntentView result = handler.handle(cmd, ownScope());

        assertThat(result.status()).isEqualTo("PAID");
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }
}
