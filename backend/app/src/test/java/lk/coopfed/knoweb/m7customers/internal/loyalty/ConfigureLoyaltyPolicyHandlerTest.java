package lk.coopfed.knoweb.m7customers.internal.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.ConfigureLoyaltyPolicy;
import lk.coopfed.knoweb.m7customers.api.LoyaltyPolicyConfigured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class ConfigureLoyaltyPolicyHandlerTest {

    private static final UUID SOCIETY = UUID.fromString("0190f700-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190f700-0000-7000-8000-000000000021");

    private JdbcTemplate jdbc;
    private ConfigRegistry config;
    private Clock clock;
    private AuditFacade audit;
    private EventPublisher events;
    private ConfigureLoyaltyPolicyHandler handler;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        config = mock(ConfigRegistry.class);
        clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        handler = new ConfigureLoyaltyPolicyHandler(jdbc, config, clock, audit, events);
    }

    private ScopeContext scope() {
        return ScopeContext.dev(USER, SOCIETY, null);
    }

    @Test
    void rejectsMissingScope() {
        ConfigureLoyaltyPolicy cmd = new ConfigureLoyaltyPolicy(
                "DEFAULT", new BigDecimal("20000.00"), 3, 200, new BigDecimal("50000.00"), true);

        assertThatThrownBy(() -> handler.handle(cmd, null))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.scope.own_required");
    }

    @Test
    void rejectsNegativeThreshold() {
        ConfigureLoyaltyPolicy cmd = new ConfigureLoyaltyPolicy(
                "DEFAULT", new BigDecimal("-1.00"), 3, 200, new BigDecimal("50000.00"), true);

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.spend_amount_invalid");
    }

    @Test
    void rejectsNegativeTenure() {
        ConfigureLoyaltyPolicy cmd = new ConfigureLoyaltyPolicy(
                "DEFAULT", new BigDecimal("20000.00"), -1, 200, new BigDecimal("50000.00"), true);

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.period_invalid");
    }

    @Test
    void insertsNewPolicyAndPublishesAuditAndEvent() {
        when(config.get(eq("customers.loyalty.monthly_spend_threshold"), any())).thenReturn(Optional.of("20000.00"));
        when(config.getInt(eq("customers.loyalty.min_membership_months"), any(), anyInt()))
                .thenReturn(3);
        when(config.getInt(eq("customers.loyalty.monthly_reward_points"), any(), anyInt()))
                .thenReturn(200);
        when(config.get(eq("customers.loyalty.insurance_benefit_amount"), any()))
                .thenReturn(Optional.of("50000.00"));

        // Query returns null (no existing policy)
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(SOCIETY), eq("DEFAULT")))
                .thenReturn(null);

        ConfigureLoyaltyPolicy cmd = new ConfigureLoyaltyPolicy(
                "DEFAULT", new BigDecimal("20000.00"), 3, 200, new BigDecimal("50000.00"), true);

        UUID policyId = handler.handle(cmd, scope());
        assertThat(policyId).isNotNull();

        // Verify SQL insert
        verify(jdbc)
                .update(
                        anyString(),
                        eq(policyId),
                        eq(SOCIETY),
                        eq("DEFAULT"),
                        eq(new BigDecimal("20000.00")),
                        eq(3),
                        eq(200),
                        eq(new BigDecimal("50000.00")),
                        eq(true),
                        any(),
                        any(),
                        eq(USER));

        // Verify audit
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(ConfigureLoyaltyPolicyHandler.AUDIT_POLICY_CONFIGURED),
                        eq(Subject.of("loyalty_reward_policy", policyId)),
                        eq(null),
                        auditCaptor.capture(),
                        any());
        assertThat(auditCaptor.getValue())
                .containsEntry("policy_name", "DEFAULT")
                .containsEntry("monthly_spend_threshold", "20000.00")
                .containsEntry("min_membership_months", 3)
                .containsEntry("monthly_reward_points", 200)
                .containsEntry("insurance_benefit_amount", "50000.00");

        // Verify domain event
        ArgumentCaptor<LoyaltyPolicyConfigured> eventCaptor = ArgumentCaptor.forClass(LoyaltyPolicyConfigured.class);
        verify(events).publish(eventCaptor.capture());
        LoyaltyPolicyConfigured event = eventCaptor.getValue();
        assertThat(event.policyId()).isEqualTo(policyId);
        assertThat(event.ownerEntityId()).isEqualTo(SOCIETY);
        assertThat(event.monthlySpendThreshold()).isEqualTo(new BigDecimal("20000.00"));
        assertThat(event.minMembershipMonths()).isEqualTo(3);
        assertThat(event.monthlyRewardPoints()).isEqualTo(200);
        assertThat(event.insuranceBenefitAmount()).isEqualTo(new BigDecimal("50000.00"));
    }
}
