package lk.coopfed.knoweb.m7customers.internal.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.LoyaltySpendRecorded;
import lk.coopfed.knoweb.m7customers.api.RecordLoyaltySpend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class RecordLoyaltySpendHandlerTest {

    private static final UUID SOCIETY = UUID.fromString("0190f700-0000-7000-8000-000000000001");
    private static final UUID SHOP = UUID.fromString("0190f700-0000-7000-8000-000000000011");
    private static final UUID USER = UUID.fromString("0190f700-0000-7000-8000-000000000021");
    private static final UUID LOYALTY_ID = UUID.fromString("0190f700-0000-7000-8000-000000000031");
    private static final UUID CUSTOMER_ID = UUID.fromString("0190f700-0000-7000-8000-000000000041");

    private JdbcTemplate jdbc;
    private Clock clock;
    private AuditFacade audit;
    private EventPublisher events;
    private RecordLoyaltySpendHandler handler;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        handler = new RecordLoyaltySpendHandler(jdbc, clock, audit, events);
    }

    private ScopeContext scope() {
        return ScopeContext.dev(USER, SOCIETY, SHOP);
    }

    @Test
    void rejectsMissingScope() {
        RecordLoyaltySpend cmd =
                new RecordLoyaltySpend(LOYALTY_ID, SHOP, LocalDate.now(clock), new BigDecimal("2500.00"), "RCP-12345");

        assertThatThrownBy(() -> handler.handle(cmd, null))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.scope.own_required");
    }

    @Test
    void rejectsInvalidAmount() {
        RecordLoyaltySpend cmd =
                new RecordLoyaltySpend(LOYALTY_ID, SHOP, LocalDate.now(clock), BigDecimal.ZERO, "RCP-12345");

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.spend_amount_invalid");
    }

    @Test
    void rejectsNonExistentLoyaltyCustomer() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(LOYALTY_ID), eq(SOCIETY)))
                .thenReturn(null);

        RecordLoyaltySpend cmd =
                new RecordLoyaltySpend(LOYALTY_ID, SHOP, LocalDate.now(clock), new BigDecimal("15000.00"), "RCP-12345");

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.not_found");
    }

    @Test
    void rejectsInactiveCustomer() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(LOYALTY_ID), eq(SOCIETY)))
                .thenReturn(Map.of("customer_id", CUSTOMER_ID, "status", "SUSPENDED"));

        RecordLoyaltySpend cmd =
                new RecordLoyaltySpend(LOYALTY_ID, SHOP, LocalDate.now(clock), new BigDecimal("15000.00"), "RCP-12345");

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.customer.not_active");
    }

    @Test
    void recordsSpendAndPublishesAuditAndEventWithoutPii() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(LOYALTY_ID), eq(SOCIETY)))
                .thenReturn(Map.of("customer_id", CUSTOMER_ID, "status", "ACTIVE"));

        LocalDate spendDate = LocalDate.parse("2026-10-15");
        RecordLoyaltySpend cmd =
                new RecordLoyaltySpend(LOYALTY_ID, SHOP, spendDate, new BigDecimal("21000.00"), "RCP-8888");

        UUID spendId = handler.handle(cmd, scope());
        assertThat(spendId).isNotNull();

        // Verify SQL insert
        verify(jdbc)
                .update(
                        anyString(),
                        eq(spendId),
                        eq(SOCIETY),
                        eq(LOYALTY_ID),
                        eq(CUSTOMER_ID),
                        eq(SHOP),
                        any(),
                        eq(new BigDecimal("21000.00")),
                        eq("RCP-8888"),
                        any(),
                        eq(USER));

        // Verify audit contains no PII
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(RecordLoyaltySpendHandler.AUDIT_SPEND_RECORDED),
                        eq(Subject.of("loyalty_spend", spendId)),
                        eq(null),
                        auditCaptor.capture(),
                        any());
        assertThat(auditCaptor.getValue())
                .containsEntry("loyalty_id", LOYALTY_ID.toString())
                .containsEntry("amount", "21000.00")
                .doesNotContainKeys("phone", "nic", "name");

        // Verify event
        ArgumentCaptor<LoyaltySpendRecorded> eventCaptor = ArgumentCaptor.forClass(LoyaltySpendRecorded.class);
        verify(events).publish(eventCaptor.capture());
        LoyaltySpendRecorded event = eventCaptor.getValue();
        assertThat(event.spendId()).isEqualTo(spendId);
        assertThat(event.loyaltyId()).isEqualTo(LOYALTY_ID);
        assertThat(event.amount()).isEqualTo(new BigDecimal("21000.00"));
        assertThat(event.spendDate()).isEqualTo(spendDate);
    }
}
