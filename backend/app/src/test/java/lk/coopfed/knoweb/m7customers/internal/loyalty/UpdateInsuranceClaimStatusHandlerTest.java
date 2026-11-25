package lk.coopfed.knoweb.m7customers.internal.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.LoyaltyInsuranceClaimStatusUpdated;
import lk.coopfed.knoweb.m7customers.api.UpdateInsuranceClaimStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class UpdateInsuranceClaimStatusHandlerTest {

    private static final UUID SOCIETY = UUID.fromString("0190f700-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190f700-0000-7000-8000-000000000021");
    private static final UUID CLAIM_ID = UUID.fromString("0190f700-0000-7000-8000-000000000051");
    private static final UUID LOYALTY_ID = UUID.fromString("0190f700-0000-7000-8000-000000000031");

    private JdbcTemplate jdbc;
    private Clock clock;
    private AuditFacade audit;
    private EventPublisher events;
    private UpdateInsuranceClaimStatusHandler handler;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        handler = new UpdateInsuranceClaimStatusHandler(jdbc, clock, audit, events);
    }

    private ScopeContext scope() {
        return ScopeContext.dev(USER, SOCIETY, null);
    }

    @Test
    void rejectsInvalidStatus() {
        UpdateInsuranceClaimStatus cmd = new UpdateInsuranceClaimStatus(CLAIM_ID, "PAID_CASH", null);

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.claim_status_invalid");
    }

    @Test
    void rejectsRejectionWithoutReason() {
        UpdateInsuranceClaimStatus cmd = new UpdateInsuranceClaimStatus(CLAIM_ID, "REJECTED", "   ");

        assertThatThrownBy(() -> handler.handle(cmd, scope()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.claim_rejection_reason_required");
    }

    @Test
    void updatesStatusAndPublishesAuditAndEvent() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(CLAIM_ID), eq(SOCIETY)))
                .thenReturn(Map.of(
                        "loyalty_id", LOYALTY_ID,
                        "claim_status", "ELIGIBLE_SUPPORTED",
                        "rejection_reason", ""));

        UpdateInsuranceClaimStatus cmd = new UpdateInsuranceClaimStatus(CLAIM_ID, "APPROVED", null);

        handler.handle(cmd, scope());

        // Verify update
        verify(jdbc).update(anyString(), eq("APPROVED"), eq(null), any(Timestamp.class), eq(USER), eq(CLAIM_ID));

        // Verify audit
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(UpdateInsuranceClaimStatusHandler.AUDIT_STATUS_UPDATED),
                        eq(Subject.of("loyalty_insurance_claim", CLAIM_ID)),
                        any(),
                        auditCaptor.capture(),
                        any());
        assertThat(auditCaptor.getValue())
                .containsEntry("claim_id", CLAIM_ID.toString())
                .containsEntry("claim_status", "APPROVED");

        // Verify event
        ArgumentCaptor<LoyaltyInsuranceClaimStatusUpdated> eventCaptor =
                ArgumentCaptor.forClass(LoyaltyInsuranceClaimStatusUpdated.class);
        verify(events).publish(eventCaptor.capture());
        LoyaltyInsuranceClaimStatusUpdated event = eventCaptor.getValue();
        assertThat(event.claimId()).isEqualTo(CLAIM_ID);
        assertThat(event.previousStatus()).isEqualTo("ELIGIBLE_SUPPORTED");
        assertThat(event.newStatus()).isEqualTo("APPROVED");
    }
}
