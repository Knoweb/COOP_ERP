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
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.CalculateMonthlyLoyaltyRewards;
import lk.coopfed.knoweb.m7customers.api.LoyaltyMonthlyRewardCalculated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class CalculateMonthlyLoyaltyRewardsHandlerTest {

    private static final UUID SOCIETY = UUID.fromString("0190f700-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190f700-0000-7000-8000-000000000021");
    private static final UUID LOYALTY_ID = UUID.fromString("0190f700-0000-7000-8000-000000000031");
    private static final UUID CUSTOMER_ID = UUID.fromString("0190f700-0000-7000-8000-000000000041");

    private JdbcTemplate jdbc;
    private ConfigRegistry config;
    private Clock clock;
    private AuditFacade audit;
    private EventPublisher events;
    private CalculateMonthlyLoyaltyRewardsHandler handler;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        config = mock(ConfigRegistry.class);
        clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        handler = new CalculateMonthlyLoyaltyRewardsHandler(jdbc, config, clock, audit, events);
    }

    private ScopeContext scope() {
        return ScopeContext.dev(USER, SOCIETY, null);
    }

    @Test
    void rejectsInvalidPeriodMonth() {
        CalculateMonthlyLoyaltyRewards cmd = new CalculateMonthlyLoyaltyRewards("2026-1", null);

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.period_invalid");
    }

    @Test
    void awardsPointsAndQualifiesInsuranceWhenTenureAndSpendMet() {
        // Policy: threshold 20,000, 3 months, 200 points
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(SOCIETY)))
                .thenReturn(Map.of(
                        "threshold",
                        new BigDecimal("20000.00"),
                        "min_months",
                        3,
                        "reward_points",
                        200,
                        "insurance_amount",
                        new BigDecimal("50000.00")));

        // Customer enrolled 4 months before 2026-10-31 (e.g. 2026-06-01)
        Timestamp enrolledAt = Timestamp.valueOf(LocalDate.of(2026, 6, 1).atStartOfDay());
        when(jdbc.queryForList(anyString(), eq(SOCIETY), eq(CUSTOMER_ID)))
                .thenReturn(List.of(Map.of(
                        "loyalty_id", LOYALTY_ID,
                        "customer_id", CUSTOMER_ID,
                        "enrolled_at", enrolledAt)));

        // Spend in month: 22,500
        when(jdbc.queryForObject(
                        anyString(),
                        eq(BigDecimal.class),
                        eq(SOCIETY),
                        eq(LOYALTY_ID),
                        any(Date.class),
                        any(Date.class)))
                .thenReturn(new BigDecimal("22500.00"));

        // No existing monthly reward row
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(SOCIETY), eq(LOYALTY_ID), eq("2026-10")))
                .thenReturn(null);

        CalculateMonthlyLoyaltyRewards cmd = new CalculateMonthlyLoyaltyRewards("2026-10", CUSTOMER_ID);

        List<UUID> result = handler.handle(cmd, scope());
        assertThat(result).hasSize(1);

        // Verify points ledger insertion (200 points)
        verify(jdbc)
                .update(
                        anyString(),
                        any(UUID.class),
                        eq(SOCIETY),
                        eq(LOYALTY_ID),
                        eq(CUSTOMER_ID),
                        eq(200),
                        eq("MONTHLY_QUALIFYING_REWARD"),
                        eq("2026-10"),
                        eq(result.get(0)),
                        any(Timestamp.class));

        // Verify audit
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(CalculateMonthlyLoyaltyRewardsHandler.AUDIT_MONTHLY_REWARD_CALCULATED),
                        eq(Subject.of("loyalty_monthly_reward", result.get(0))),
                        eq(null),
                        auditCaptor.capture(),
                        any());
        assertThat(auditCaptor.getValue())
                .containsEntry("loyalty_id", LOYALTY_ID.toString())
                .containsEntry("period_month", "2026-10")
                .containsEntry("qualifying_spend", "22500.00")
                .containsEntry("points_awarded", 200)
                .containsEntry("insurance_eligible", true)
                .containsEntry("reward_status", "AWARDED");

        // Verify domain event
        ArgumentCaptor<LoyaltyMonthlyRewardCalculated> eventCaptor =
                ArgumentCaptor.forClass(LoyaltyMonthlyRewardCalculated.class);
        verify(events).publish(eventCaptor.capture());
        LoyaltyMonthlyRewardCalculated event = eventCaptor.getValue();
        assertThat(event.loyaltyId()).isEqualTo(LOYALTY_ID);
        assertThat(event.periodMonth()).isEqualTo("2026-10");
        assertThat(event.qualifyingSpend()).isEqualTo(new BigDecimal("22500.00"));
        assertThat(event.pointsAwarded()).isEqualTo(200);
        assertThat(event.insuranceEligible()).isTrue();
        assertThat(event.rewardStatus()).isEqualTo("AWARDED");
    }

    @Test
    void disqualifiesWhenSpendIsUnderThreshold() {
        // Policy: threshold 20,000, 3 months, 200 points
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(SOCIETY)))
                .thenReturn(Map.of(
                        "threshold",
                        new BigDecimal("20000.00"),
                        "min_months",
                        3,
                        "reward_points",
                        200,
                        "insurance_amount",
                        new BigDecimal("50000.00")));

        // Customer enrolled 4 months before 2026-10-31
        Timestamp enrolledAt = Timestamp.valueOf(LocalDate.of(2026, 6, 1).atStartOfDay());
        when(jdbc.queryForList(anyString(), eq(SOCIETY), eq(CUSTOMER_ID)))
                .thenReturn(List.of(Map.of(
                        "loyalty_id", LOYALTY_ID,
                        "customer_id", CUSTOMER_ID,
                        "enrolled_at", enrolledAt)));

        // Spend in month: 14,000 (< 20,000)
        when(jdbc.queryForObject(
                        anyString(),
                        eq(BigDecimal.class),
                        eq(SOCIETY),
                        eq(LOYALTY_ID),
                        any(Date.class),
                        any(Date.class)))
                .thenReturn(new BigDecimal("14000.00"));

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(SOCIETY), eq(LOYALTY_ID), eq("2026-10")))
                .thenReturn(null);

        CalculateMonthlyLoyaltyRewards cmd = new CalculateMonthlyLoyaltyRewards("2026-10", CUSTOMER_ID);

        List<UUID> result = handler.handle(cmd, scope());
        assertThat(result).hasSize(1);

        // Verify domain event shows disqualified and 0 points
        ArgumentCaptor<LoyaltyMonthlyRewardCalculated> eventCaptor =
                ArgumentCaptor.forClass(LoyaltyMonthlyRewardCalculated.class);
        verify(events).publish(eventCaptor.capture());
        LoyaltyMonthlyRewardCalculated event = eventCaptor.getValue();
        assertThat(event.pointsAwarded()).isEqualTo(0);
        assertThat(event.insuranceEligible()).isFalse();
        assertThat(event.rewardStatus()).isEqualTo("DISQUALIFIED");
    }

    @Test
    void disqualifiesWhenTenureIsUnderMinimumMonths() {
        // Policy: threshold 20,000, 3 months, 200 points
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(SOCIETY)))
                .thenReturn(Map.of(
                        "threshold",
                        new BigDecimal("20000.00"),
                        "min_months",
                        3,
                        "reward_points",
                        200,
                        "insurance_amount",
                        new BigDecimal("50000.00")));

        // Customer enrolled only 1 month before (2026-09-15)
        Timestamp enrolledAt = Timestamp.valueOf(LocalDate.of(2026, 9, 15).atStartOfDay());
        when(jdbc.queryForList(anyString(), eq(SOCIETY), eq(CUSTOMER_ID)))
                .thenReturn(List.of(Map.of(
                        "loyalty_id", LOYALTY_ID,
                        "customer_id", CUSTOMER_ID,
                        "enrolled_at", enrolledAt)));

        // Spend in month: 25,000 (qualifying spend met, but tenure not met)
        when(jdbc.queryForObject(
                        anyString(),
                        eq(BigDecimal.class),
                        eq(SOCIETY),
                        eq(LOYALTY_ID),
                        any(Date.class),
                        any(Date.class)))
                .thenReturn(new BigDecimal("25000.00"));

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(SOCIETY), eq(LOYALTY_ID), eq("2026-10")))
                .thenReturn(null);

        CalculateMonthlyLoyaltyRewards cmd = new CalculateMonthlyLoyaltyRewards("2026-10", CUSTOMER_ID);

        List<UUID> result = handler.handle(cmd, scope());
        assertThat(result).hasSize(1);

        ArgumentCaptor<LoyaltyMonthlyRewardCalculated> eventCaptor =
                ArgumentCaptor.forClass(LoyaltyMonthlyRewardCalculated.class);
        verify(events).publish(eventCaptor.capture());
        LoyaltyMonthlyRewardCalculated event = eventCaptor.getValue();
        assertThat(event.pointsAwarded()).isEqualTo(0);
        assertThat(event.insuranceEligible()).isFalse();
        assertThat(event.rewardStatus()).isEqualTo("DISQUALIFIED");
    }
}
