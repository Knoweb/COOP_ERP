package lk.coopfed.knoweb.m9integration.internal.hr;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.ApproveOvertime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ApproveOvertimeHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Clock clock = Clock.systemUTC();
    private final ApproveOvertimeHandler handler = new ApproveOvertimeHandler(jdbc, audit, events, clock);

    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ENTITY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");

    private static ScopeContext ownScope() {
        Scope s = new Scope(ENTITY_ID, null);
        return new ScopeContext(
                USER_ID, null, ENTITY_ID, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private static ScopeContext fedViewScope() {
        Scope s = new Scope(ENTITY_ID, null);
        return new ScopeContext(
                USER_ID,
                null,
                ENTITY_ID,
                List.of(s),
                s,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }

    @Test
    void rejectsNonOwnScope() {
        ApproveOvertime cmd = new ApproveOvertime(UUID.randomUUID(), "2026-10", "Approved");
        assertThatThrownBy(() -> handler.handle(cmd, fedViewScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.journal.own_required");
    }

    @Test
    void rejectsNullCommand() {
        assertThatThrownBy(() -> handler.handle(null, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("request.invalid");
    }

    @Test
    void rejectsNullCalculationId() {
        ApproveOvertime cmd = new ApproveOvertime(null, "2026-10", "Approved");
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("request.invalid");
    }

    @Test
    void rejectsInvalidPayrollPeriodFormat() {
        ApproveOvertime cmd1 = new ApproveOvertime(UUID.randomUUID(), "2026-13", "Approved");
        assertThatThrownBy(() -> handler.handle(cmd1, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.overtime.period_invalid");

        ApproveOvertime cmd2 = new ApproveOvertime(UUID.randomUUID(), "invalid-period", "Approved");
        assertThatThrownBy(() -> handler.handle(cmd2, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.overtime.period_invalid");
    }
}
