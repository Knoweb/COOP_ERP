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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.AttendanceImportResult;
import lk.coopfed.knoweb.m9integration.api.AttendanceImported;
import lk.coopfed.knoweb.m9integration.api.ImportAttendanceRecords;
import lk.coopfed.knoweb.m9integration.api.ImportAttendanceRecords.AttendanceImportItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ImportAttendanceRecordsHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final ConfigRegistry config = mock(ConfigRegistry.class);
    private final Clock clock = Clock.systemUTC();
    private final ImportAttendanceRecordsHandler handler =
            new ImportAttendanceRecordsHandler(jdbc, audit, events, config, clock);

    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ENTITY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID EMP_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000020");

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
        ImportAttendanceRecords cmd = new ImportAttendanceRecords(List.of());
        assertThatThrownBy(() -> handler.handle(cmd, fedViewScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.journal.own_required");
        verifyNoInteractions(audit);
        verifyNoInteractions(events);
    }

    @Test
    void rejectsNullCommand() {
        assertThatThrownBy(() -> handler.handle(null, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("request.invalid");
        verifyNoInteractions(audit);
        verifyNoInteractions(events);
    }

    @Test
    void rejectsNullItems() {
        assertThatThrownBy(() -> handler.handle(new ImportAttendanceRecords(null), ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("request.invalid");
        verifyNoInteractions(audit);
        verifyNoInteractions(events);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulImportPublishesEventAndAudits() {
        Instant in = Instant.parse("2026-10-21T08:00:00Z");
        Instant out = Instant.parse("2026-10-21T17:00:00Z");
        AttendanceImportItem item =
                new AttendanceImportItem("P1", "E100", "DEV-1", "ZKTECO", LocalDate.parse("2026-10-21"), in, out, "{}");
        ImportAttendanceRecords cmd = new ImportAttendanceRecords(List.of(item));

        // Deduplication returns empty -> new punch
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY_ID), eq("ZKTECO"), eq("P1")))
                .thenReturn(List.of());
        // Employee lookup returns valid employee
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY_ID), eq("E100")))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("employee_id")).thenReturn(EMP_ID);
                    when(rs.getBigDecimal("hourly_rate")).thenReturn(new BigDecimal("400.00"));
                    return List.of(mapper.mapRow(rs, 0));
                });

        AttendanceImportResult result = handler.handle(cmd, ownScope());

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.duplicate()).isZero();
        assertThat(result.rejected()).isZero();

        verify(audit).record(eq(ImportAttendanceRecordsHandler.AUDIT_IMPORTED), any(), any(), any(), any());

        ArgumentCaptor<AttendanceImported> captor = ArgumentCaptor.forClass(AttendanceImported.class);
        verify(events).publish(captor.capture());
        AttendanceImported event = captor.getValue();
        assertThat(event.batchId()).isNotNull();
        assertThat(event.ownerEntityId()).isEqualTo(ENTITY_ID);
        assertThat(event.sourceProvider()).isEqualTo("ZKTECO");
        assertThat(event.importedCount()).isEqualTo(1);
        assertThat(event.duplicateCount()).isZero();
        assertThat(event.rejectedCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateOnlyBatchDoesNotPublishEvent() {
        Instant in = Instant.parse("2026-10-21T08:00:00Z");
        Instant out = Instant.parse("2026-10-21T17:00:00Z");
        AttendanceImportItem item =
                new AttendanceImportItem("P1", "E100", "DEV-1", "ZKTECO", LocalDate.parse("2026-10-21"), in, out, "{}");
        ImportAttendanceRecords cmd = new ImportAttendanceRecords(List.of(item));

        // Deduplication returns existing attendanceId -> duplicate punch
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY_ID), eq("ZKTECO"), eq("P1")))
                .thenReturn(List.of(UUID.randomUUID()));

        AttendanceImportResult result = handler.handle(cmd, ownScope());

        assertThat(result.imported()).isZero();
        assertThat(result.duplicate()).isEqualTo(1);
        assertThat(result.rejected()).isZero();

        verify(audit).record(eq(ImportAttendanceRecordsHandler.AUDIT_IMPORTED), any(), any(), any(), any());
        // Zero state mutation -> no domain event published
        verifyNoInteractions(events);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedOnlyBatchDoesNotPublishEvent() {
        Instant in = Instant.parse("2026-10-21T08:00:00Z");
        Instant out = Instant.parse("2026-10-21T17:00:00Z");
        AttendanceImportItem item = new AttendanceImportItem(
                "P1", "UNKNOWN", "DEV-1", "ZKTECO", LocalDate.parse("2026-10-21"), in, out, "{}");
        ImportAttendanceRecords cmd = new ImportAttendanceRecords(List.of(item));

        // Deduplication returns empty
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY_ID), eq("ZKTECO"), eq("P1")))
                .thenReturn(List.of());
        // Employee lookup returns empty (not found)
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY_ID), eq("UNKNOWN")))
                .thenReturn(List.of());

        AttendanceImportResult result = handler.handle(cmd, ownScope());

        assertThat(result.imported()).isZero();
        assertThat(result.duplicate()).isZero();
        assertThat(result.rejected()).isEqualTo(1);

        verify(audit).record(eq(ImportAttendanceRecordsHandler.AUDIT_IMPORTED), any(), any(), any(), any());
        verifyNoInteractions(events);
    }
}
