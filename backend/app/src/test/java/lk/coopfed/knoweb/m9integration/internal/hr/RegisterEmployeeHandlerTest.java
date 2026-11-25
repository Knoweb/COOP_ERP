package lk.coopfed.knoweb.m9integration.internal.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import lk.coopfed.knoweb.m9integration.api.EmployeeRegistered;
import lk.coopfed.knoweb.m9integration.api.RegisterEmployee;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RegisterEmployeeHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Clock clock = Clock.systemUTC();
    private final RegisterEmployeeHandler handler = new RegisterEmployeeHandler(jdbc, audit, events, clock);

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
        RegisterEmployee cmd = new RegisterEmployee("E100", "Kamal Perera", null, null, new BigDecimal("450.00"), null);
        assertThatThrownBy(() -> handler.handle(cmd, fedViewScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.journal.own_required");
        verifyNoInteractions(audit);
        verifyNoInteractions(events);
    }

    @Test
    void rejectsBlankEmployeeNo() {
        RegisterEmployee cmd = new RegisterEmployee("   ", "Kamal Perera", null, null, new BigDecimal("450.00"), null);
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("request.invalid");
        verifyNoInteractions(audit);
        verifyNoInteractions(events);
    }

    @Test
    void rejectsBlankFullName() {
        RegisterEmployee cmd = new RegisterEmployee("E100", "", null, null, new BigDecimal("450.00"), null);
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("request.invalid");
        verifyNoInteractions(audit);
        verifyNoInteractions(events);
    }

    @Test
    void rejectsNegativeHourlyRate() {
        RegisterEmployee cmd = new RegisterEmployee("E100", "Kamal Perera", null, null, new BigDecimal("-10.00"), null);
        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("request.invalid");
        verifyNoInteractions(audit);
        verifyNoInteractions(events);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulRegistrationPublishesEventAndAudits() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY_ID), eq("E100")))
                .thenReturn(List.of());

        RegisterEmployee cmd =
                new RegisterEmployee("E100", "Kamal Perera", "199012345678", "EPF999", new BigDecimal("450.00"), null);
        UUID empId = handler.handle(cmd, ownScope());

        assertThat(empId).isNotNull();
        verify(audit).record(eq(RegisterEmployeeHandler.AUDIT_REGISTERED), any(), any(), any(), any());

        ArgumentCaptor<EmployeeRegistered> captor = ArgumentCaptor.forClass(EmployeeRegistered.class);
        verify(events).publish(captor.capture());
        EmployeeRegistered event = captor.getValue();
        assertThat(event.employeeId()).isEqualTo(empId);
        assertThat(event.employeeNo()).isEqualTo("E100");
        assertThat(event.ownerEntityId()).isEqualTo(ENTITY_ID);
        assertThat(event.locationId()).isNull();
    }
}
