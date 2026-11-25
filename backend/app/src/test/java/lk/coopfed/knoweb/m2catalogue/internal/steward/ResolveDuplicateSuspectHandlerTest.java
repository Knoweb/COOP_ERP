package lk.coopfed.knoweb.m2catalogue.internal.steward;

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
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.DuplicateSuspectResolved;
import lk.coopfed.knoweb.m2catalogue.api.ResolveDuplicateSuspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ResolveDuplicateSuspectHandlerTest {

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private Clock clock;
    private ResolveDuplicateSuspectHandler handler;

    private static final UUID ENTITY = UUID.fromString("0190e620-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e620-0000-7000-8000-000000000010");
    private static final ScopeContext SCOPE = new ScopeContext(
            USER,
            null,
            ENTITY,
            List.of(new lk.coopfed.knoweb.kernel.api.Scope(ENTITY, null)),
            new lk.coopfed.knoweb.kernel.api.Scope(ENTITY, null),
            PolicyClass.OWN,
            java.util.Set.of(),
            null,
            java.util.Locale.ENGLISH,
            null);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
        handler = new ResolveDuplicateSuspectHandler(jdbc, audit, events, clock);
    }

    @Test
    void rejectsMissingScope() {
        ResolveDuplicateSuspect cmd = new ResolveDuplicateSuspect(UUID.randomUUID(), "MERGED", null);
        assertThatThrownBy(() -> handler.handle(cmd, null))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m2.sku.scope_required");
    }

    @Test
    void rejectsMissingSuspectId() {
        ResolveDuplicateSuspect cmd = new ResolveDuplicateSuspect(null, "MERGED", null);
        assertThatThrownBy(() -> handler.handle(cmd, SCOPE))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("request.invalid");
    }

    @Test
    void rejectsInvalidDecision() {
        ResolveDuplicateSuspect cmd = new ResolveDuplicateSuspect(UUID.randomUUID(), "UNKNOWN_DECISION", null);
        assertThatThrownBy(() -> handler.handle(cmd, SCOPE))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m2.suspect.decision_invalid");
    }

    @Test
    void rejectsWhenSuspectNotFoundOrAlreadyClosed() {
        UUID suspectId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);

        ResolveDuplicateSuspect cmd = new ResolveDuplicateSuspect(suspectId, "MERGED", null);
        assertThatThrownBy(() -> handler.handle(cmd, SCOPE))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m2.suspect.not_found_or_closed");
    }

    @Test
    void successfullyResolvesSuspectAsMerged() {
        UUID suspectId = UUID.randomUUID();
        UUID canonicalId = UUID.randomUUID();

        when(jdbc.update(
                        anyString(),
                        eq("MERGED"),
                        eq(USER),
                        eq(Timestamp.from(Instant.parse("2026-10-15T12:00:00Z"))),
                        eq(suspectId),
                        eq(ENTITY)))
                .thenReturn(1);

        ResolveDuplicateSuspect cmd = new ResolveDuplicateSuspect(suspectId, "MERGED", canonicalId);
        handler.handle(cmd, SCOPE);

        // Verify audit record
        verify(audit).record(eq(ResolveDuplicateSuspectHandler.AUDIT_SUSPECT_RESOLVED), any(), any(), any(), eq(SCOPE));

        // Verify domain event
        ArgumentCaptor<DuplicateSuspectResolved> captor = ArgumentCaptor.forClass(DuplicateSuspectResolved.class);
        verify(events).publish(captor.capture());
        DuplicateSuspectResolved evt = captor.getValue();
        assertThat(evt.suspectId()).isEqualTo(suspectId);
        assertThat(evt.decision()).isEqualTo("MERGED");
        assertThat(evt.decidedBy()).isEqualTo(USER);
        assertThat(evt.ownerEntityId()).isEqualTo(ENTITY);
    }

    @Test
    void successfullyResolvesSuspectAsDismissed() {
        UUID suspectId = UUID.randomUUID();

        when(jdbc.update(
                        anyString(),
                        eq("DISMISSED"),
                        eq(USER),
                        eq(Timestamp.from(Instant.parse("2026-10-15T12:00:00Z"))),
                        eq(suspectId),
                        eq(ENTITY)))
                .thenReturn(1);

        ResolveDuplicateSuspect cmd = new ResolveDuplicateSuspect(suspectId, "DISMISSED", null);
        handler.handle(cmd, SCOPE);

        verify(audit).record(eq(ResolveDuplicateSuspectHandler.AUDIT_SUSPECT_RESOLVED), any(), any(), any(), eq(SCOPE));

        ArgumentCaptor<DuplicateSuspectResolved> captor = ArgumentCaptor.forClass(DuplicateSuspectResolved.class);
        verify(events).publish(captor.capture());
        assertThat(captor.getValue().decision()).isEqualTo("DISMISSED");
    }
}
