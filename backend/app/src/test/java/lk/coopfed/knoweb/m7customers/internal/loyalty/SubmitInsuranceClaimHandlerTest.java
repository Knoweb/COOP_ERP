package lk.coopfed.knoweb.m7customers.internal.loyalty;

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
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.LoyaltyInsuranceClaimSubmitted;
import lk.coopfed.knoweb.m7customers.api.SubmitInsuranceClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class SubmitInsuranceClaimHandlerTest {

    private static final UUID SOCIETY = UUID.fromString("0190f700-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190f700-0000-7000-8000-000000000021");
    private static final UUID LOYALTY_ID = UUID.fromString("0190f700-0000-7000-8000-000000000031");
    private static final UUID CUSTOMER_ID = UUID.fromString("0190f700-0000-7000-8000-000000000041");

    private JdbcTemplate jdbc;
    private ConfigRegistry config;
    private Clock clock;
    private ObjectMapper json;
    private AuditFacade audit;
    private EventPublisher events;
    private SubmitInsuranceClaimHandler handler;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        config = mock(ConfigRegistry.class);
        clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);
        json = new ObjectMapper();
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        handler = new SubmitInsuranceClaimHandler(jdbc, config, clock, json, audit, events);
    }

    private ScopeContext scope() {
        return ScopeContext.dev(USER, SOCIETY, null);
    }

    @Test
    void rejectsInvalidClaimType() {
        SubmitInsuranceClaim cmd =
                new SubmitInsuranceClaim(LOYALTY_ID, "LOTTERY_WIN", LocalDate.now(clock), "SPOUSE", "DOC-001");

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.claim_type_invalid");
    }

    @Test
    void rejectsMissingSupportingDocRef() {
        SubmitInsuranceClaim cmd =
                new SubmitInsuranceClaim(LOYALTY_ID, "DEATH_BENEFIT", LocalDate.now(clock), "SPOUSE", " ");

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.claim_doc_ref_required");
    }

    @Test
    void rejectsFutureIncidentDate() {
        SubmitInsuranceClaim cmd = new SubmitInsuranceClaim(
                LOYALTY_ID, "DEATH_BENEFIT", LocalDate.now(clock).plusDays(2), "SPOUSE", "DOC-001");

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.claim_incident_date_invalid");
    }

    @Test
    void submitsDeathBenefitClaimWithVerifiedEligibilityWithoutCashPayout() {
        LocalDate incidentDate = LocalDate.parse("2026-10-15");
        Timestamp enrolledAt = Timestamp.valueOf(LocalDate.of(2026, 6, 1).atStartOfDay());

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(LOYALTY_ID), eq(SOCIETY)))
                .thenReturn(Map.of(
                        "customer_id", CUSTOMER_ID,
                        "enrolled_at", enrolledAt,
                        "status", "ACTIVE"));

        // Policy: threshold 20,000, 3 months, 50,000 benefit
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(SOCIETY)))
                .thenReturn(Map.of(
                        "threshold", new BigDecimal("20000.00"),
                        "min_months", 3,
                        "insurance_amount", new BigDecimal("50000.00")));

        // Spend in incident month: 24,000
        when(jdbc.queryForObject(
                        anyString(),
                        eq(BigDecimal.class),
                        eq(SOCIETY),
                        eq(LOYALTY_ID),
                        any(Date.class),
                        any(Date.class)))
                .thenReturn(new BigDecimal("24000.00"));

        // Previous monthly reward
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(SOCIETY), eq(LOYALTY_ID), eq("2026-10")))
                .thenReturn(true);

        SubmitInsuranceClaim cmd =
                new SubmitInsuranceClaim(LOYALTY_ID, "DEATH_BENEFIT", incidentDate, "SPOUSE", "DOC-DEATH-CERT-101");

        UUID claimId = handler.handle(cmd, scope());
        assertThat(claimId).isNotNull();

        // Verify SQL insert contains ELIGIBLE_SUPPORTED and benefit amount 50000.00
        verify(jdbc)
                .update(
                        anyString(),
                        eq(claimId),
                        eq(SOCIETY),
                        eq(LOYALTY_ID),
                        eq(CUSTOMER_ID),
                        eq("DEATH_BENEFIT"),
                        eq("ELIGIBLE_SUPPORTED"),
                        eq(new BigDecimal("50000.00")),
                        eq(Date.valueOf(incidentDate)),
                        eq("SPOUSE"),
                        eq("DOC-DEATH-CERT-101"),
                        eq(true),
                        anyString(),
                        any(Timestamp.class),
                        eq(USER),
                        any(Timestamp.class),
                        eq(USER));

        // Verify audit contains no PII
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(SubmitInsuranceClaimHandler.AUDIT_CLAIM_SUBMITTED),
                        eq(Subject.of("loyalty_insurance_claim", claimId)),
                        eq(null),
                        auditCaptor.capture(),
                        any());
        assertThat(auditCaptor.getValue())
                .containsEntry("claim_type", "DEATH_BENEFIT")
                .containsEntry("claim_status", "ELIGIBLE_SUPPORTED")
                .containsEntry("policy_benefit_amount", "50000.00")
                .containsEntry("eligibility_verified", true)
                .doesNotContainKeys("phone", "nic", "name", "claimant");

        // Verify domain event
        ArgumentCaptor<LoyaltyInsuranceClaimSubmitted> eventCaptor =
                ArgumentCaptor.forClass(LoyaltyInsuranceClaimSubmitted.class);
        verify(events).publish(eventCaptor.capture());
        LoyaltyInsuranceClaimSubmitted event = eventCaptor.getValue();
        assertThat(event.claimId()).isEqualTo(claimId);
        assertThat(event.claimType()).isEqualTo("DEATH_BENEFIT");
        assertThat(event.claimStatus()).isEqualTo("ELIGIBLE_SUPPORTED");
        assertThat(event.eligibilityVerified()).isTrue();
        assertThat(event.policyBenefitAmount()).isEqualTo(new BigDecimal("50000.00"));
    }
}
