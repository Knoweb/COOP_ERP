package lk.coopfed.knoweb.m1party.internal.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DocumentType;
import lk.coopfed.knoweb.kernel.api.DocumentTypes;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.PinHasher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesScope;
import lk.coopfed.knoweb.m1party.api.BulkNetworkRegistered;
import lk.coopfed.knoweb.m1party.api.BulkNetworkReport;
import lk.coopfed.knoweb.m1party.api.BulkRegisterNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

class BulkRegisterNetworkHandlerTest {

    private JdbcTemplate jdbc;
    private PinHasher pinHasher;
    private NumberingService numbering;
    private DocumentTypes documentTypes;
    private AuditFacade audit;
    private EventPublisher events;
    private Clock clock;
    private BulkRegisterNetworkHandler handler;

    private static final UUID ENTITY_ID = UUID.fromString("0190e000-0000-7000-8000-000000000001");
    private static final ScopeContext SCOPE = ScopeContext.dev(UUID.randomUUID(), ENTITY_ID, null);

    @BeforeEach
    void setUp() {
        System.clearProperty("coop-erp.bulk-register.diagnostics");
        System.clearProperty("coop-erp.bulk-register.argon2-concurrency");
        BulkRegisterNetworkHandler.clearTelemetry();

        jdbc = mock(JdbcTemplate.class);
        pinHasher = mock(PinHasher.class);
        numbering = mock(NumberingService.class);
        documentTypes = mock(DocumentTypes.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("UTC"));

        handler = new BulkRegisterNetworkHandler(jdbc, pinHasher, numbering, documentTypes, audit, events, clock);
    }

    @Test
    void rejectsWhenScopeContextOrEntityIdIsNull() {
        BulkRegisterNetwork cmd = new BulkRegisterNetwork(List.of(), List.of(), List.of());

        assertThatThrownBy(() -> handler.handle(cmd, null))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m1.location.scope_required");
    }

    @Test
    void rejectsWhenOwnerEntityNotFoundInDatabase() {
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());

        BulkRegisterNetwork cmd = new BulkRegisterNetwork(List.of(), List.of(), List.of());

        assertThatThrownBy(() -> handler.handle(cmd, SCOPE))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m1.location.owner_not_trading");
    }

    @Test
    void catchesPayloadValidationErrorsAllOrNone() {
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("party.entity")) {
                        return List.of("MPCS01");
                    }
                    return List.of();
                });

        // Duplicate shop code in payload + invalid PIN + invalid username + unknown shop on device
        BulkRegisterNetwork cmd = new BulkRegisterNetwork(
                List.of(
                        new BulkRegisterNetwork.Shop(
                                "SH01", "Shop 1", null, null, null, "Colombo", null, null, "en", "M", List.of(1)),
                        new BulkRegisterNetwork.Shop(
                                "SH01",
                                "Shop 1 Duplicate",
                                null,
                                null,
                                null,
                                "Colombo",
                                null,
                                null,
                                "en",
                                "M",
                                List.of(2))),
                List.of(
                        new BulkRegisterNetwork.User("@invalid_user!", "User 1", "en", "TILL", "1234", "SH01"),
                        new BulkRegisterNetwork.User("user2", "User 2", "en", "TILL", "12", "SH01")), // PIN too short
                List.of(new BulkRegisterNetwork.Device("SER1", "POS_TERMINAL", "NON_EXISTENT_SHOP", 1, "1.0.0")));

        BulkNetworkReport report = handler.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(BulkNetworkReport.REJECTED);
        assertThat(report.shopsRegistered()).isZero();
        assertThat(report.usersRegistered()).isZero();

        List<String> problemCodes =
                report.problems().stream().map(BulkNetworkReport.Problem::code).toList();
        assertThat(problemCodes)
                .contains(
                        "bulk.shop.code_duplicate",
                        "bulk.user.username_invalid",
                        "bulk.user.pin_invalid",
                        "bulk.device.location_unknown");
    }

    @Test
    void successfulRegistrationCommitsAuditAndPublishesEvent() {
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("party.entity")) {
                        return List.of("MPCS01");
                    }
                    return List.of();
                });

        // No duplicates in DB
        when(jdbc.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of());
        when(jdbc.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenAnswer(inv -> {
                    BatchPreparedStatementSetter setter = inv.getArgument(1);
                    java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
                    for (int i = 0; i < setter.getBatchSize(); i++) {
                        setter.setValues(ps, i);
                    }
                    return new int[setter.getBatchSize()];
                });

        when(documentTypes.all())
                .thenReturn(List.of(new DocumentType(
                        "RECEIPT",
                        "Receipt",
                        "ලදුපත",
                        "பற்றுச்சீட்டு",
                        SeriesScope.LOCATION,
                        "pos",
                        false,
                        true,
                        true,
                        "m6pos")));

        when(pinHasher.hash(anyString())).thenReturn("$argon2id$mockedhash");

        BulkRegisterNetwork cmd = new BulkRegisterNetwork(
                List.of(new BulkRegisterNetwork.Shop(
                        "SH01", "Shop 1", null, null, null, "Colombo", null, null, "en", "M", List.of(1))),
                List.of(new BulkRegisterNetwork.User("cashier1", "Cashier One", "en", "TILL", "1234", "SH01")),
                List.of(new BulkRegisterNetwork.Device("POS-100", "POS_TERMINAL", "SH01", 1, "1.0.0")));

        BulkNetworkReport report = handler.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(BulkNetworkReport.REGISTERED);
        assertThat(report.shopsRegistered()).isEqualTo(1);
        assertThat(report.positionsRegistered()).isEqualTo(1);
        assertThat(report.usersRegistered()).isEqualTo(1);
        assertThat(report.devicesRegistered()).isEqualTo(1);
        assertThat(report.problems()).isEmpty();

        // Verify audit and event
        verify(audit).record(eq(BulkRegisterNetworkHandler.AUDIT_NETWORK_REGISTERED), any(), any(), any(), eq(SCOPE));
        verify(events).publish(any(BulkNetworkRegistered.class));
    }

    @Test
    void hashingFailureRollsBackWithoutDatabaseWritesOrAudit() {
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("party.entity")) {
                        return List.of("MPCS01");
                    }
                    return List.of();
                });

        when(pinHasher.hash(anyString())).thenThrow(new IllegalStateException("Crypto failure"));

        BulkRegisterNetwork cmd = new BulkRegisterNetwork(
                List.of(new BulkRegisterNetwork.Shop(
                        "SH01", "Shop 1", null, null, null, "Colombo", null, null, "en", "M", List.of(1))),
                List.of(new BulkRegisterNetwork.User("cashier1", "Cashier One", "en", "TILL", "1234", "SH01")),
                List.of());

        assertThatThrownBy(() -> handler.handle(cmd, SCOPE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Crypto failure");

        // Verify no batch inserts, audit, or events
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
                .batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.never()).record(any(), any(), any(), any(), any());
        org.mockito.Mockito.verify(events, org.mockito.Mockito.never()).publish(any());
    }

    @Test
    void tillPositionSeriesRegistersWithAssignedHolderDeviceIdDirectly() {
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("party.entity")) {
                        return List.of("MPCS01");
                    }
                    return List.of();
                });

        when(documentTypes.all())
                .thenReturn(List.of(new DocumentType(
                        "RCT",
                        "Receipt",
                        "ලදුපත",
                        "பற்றுச்சීட்டு",
                        SeriesScope.TILL_POSITION,
                        "pos",
                        false,
                        true,
                        true,
                        "m6pos")));

        when(pinHasher.hash(anyString())).thenReturn("$argon2id$mockedhash");

        BulkRegisterNetwork cmd = new BulkRegisterNetwork(
                List.of(new BulkRegisterNetwork.Shop(
                        "SH01", "Shop 1", null, null, null, "Colombo", null, null, "en", "M", List.of(1))),
                List.of(),
                List.of(new BulkRegisterNetwork.Device("POS-100", "POS_TERMINAL", "SH01", 1, "1.0.0")));

        BulkNetworkReport report = handler.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(BulkNetworkReport.REGISTERED);

        org.mockito.ArgumentCaptor<lk.coopfed.knoweb.kernel.api.SeriesRegistration> captor =
                org.mockito.ArgumentCaptor.forClass(lk.coopfed.knoweb.kernel.api.SeriesRegistration.class);
        verify(numbering).registerSeries(captor.capture(), eq(SCOPE));

        lk.coopfed.knoweb.kernel.api.SeriesRegistration reg = captor.getValue();
        assertThat(reg.docTypeCode()).isEqualTo("RCT");
        assertThat(reg.scope()).isEqualTo(SeriesScope.TILL_POSITION);
        assertThat(reg.holderDeviceId()).isNotNull();
    }

    @Test
    void resolveConcurrencyReturnsSafeDefaultWhenDiagnosticsDisabled() {
        System.clearProperty("coop-erp.bulk-register.diagnostics");
        System.setProperty("coop-erp.bulk-register.argon2-concurrency", "1");
        try {
            int concurrency = BulkRegisterNetworkHandler.resolveConcurrency();
            int expectedDefault = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
            assertThat(concurrency).isEqualTo(expectedDefault);
        } finally {
            System.clearProperty("coop-erp.bulk-register.argon2-concurrency");
        }
    }

    @Test
    void resolveConcurrencyAppliesBoundedOverrideWhenDiagnosticsEnabled() {
        System.setProperty("coop-erp.bulk-register.diagnostics", "true");
        try {
            System.setProperty("coop-erp.bulk-register.argon2-concurrency", "1");
            assertThat(BulkRegisterNetworkHandler.resolveConcurrency()).isEqualTo(1);

            System.setProperty("coop-erp.bulk-register.argon2-concurrency", "2");
            assertThat(BulkRegisterNetworkHandler.resolveConcurrency()).isEqualTo(2);

            System.setProperty("coop-erp.bulk-register.argon2-concurrency", "4");
            assertThat(BulkRegisterNetworkHandler.resolveConcurrency()).isEqualTo(4);
        } finally {
            System.clearProperty("coop-erp.bulk-register.diagnostics");
            System.clearProperty("coop-erp.bulk-register.argon2-concurrency");
        }
    }

    @Test
    void resolveConcurrencyRejectsOutOfRangeOrMalformedOverrideWhenDiagnosticsEnabled() {
        System.setProperty("coop-erp.bulk-register.diagnostics", "true");
        int expectedDefault = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
        try {
            // Out of range: too low
            System.setProperty("coop-erp.bulk-register.argon2-concurrency", "0");
            assertThat(BulkRegisterNetworkHandler.resolveConcurrency()).isEqualTo(expectedDefault);

            System.setProperty("coop-erp.bulk-register.argon2-concurrency", "-5");
            assertThat(BulkRegisterNetworkHandler.resolveConcurrency()).isEqualTo(expectedDefault);

            // Out of range: too high
            System.setProperty("coop-erp.bulk-register.argon2-concurrency", "5");
            assertThat(BulkRegisterNetworkHandler.resolveConcurrency()).isEqualTo(expectedDefault);

            System.setProperty("coop-erp.bulk-register.argon2-concurrency", "100");
            assertThat(BulkRegisterNetworkHandler.resolveConcurrency()).isEqualTo(expectedDefault);

            // Malformed
            System.setProperty("coop-erp.bulk-register.argon2-concurrency", "not-a-number");
            assertThat(BulkRegisterNetworkHandler.resolveConcurrency()).isEqualTo(expectedDefault);
        } finally {
            System.clearProperty("coop-erp.bulk-register.diagnostics");
            System.clearProperty("coop-erp.bulk-register.argon2-concurrency");
        }
    }

    @Test
    void telemetryIsolationAndClearingBehavior() {
        BulkRegisterNetworkHandler.clearTelemetry();
        assertThat(BulkRegisterNetworkHandler.lastTelemetry()).isNull();
        assertThat(BulkRegisterNetworkHandler.consumeTelemetry()).isNull();

        // When diagnostics is disabled, handler handle() does not populate telemetry
        System.clearProperty("coop-erp.bulk-register.diagnostics");
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("party.entity")) {
                        return List.of("MPCS01");
                    }
                    return List.of();
                });
        when(jdbc.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of());
        when(jdbc.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[0]);
        when(documentTypes.all()).thenReturn(List.of());

        BulkRegisterNetwork cmd = new BulkRegisterNetwork(
                List.of(new BulkRegisterNetwork.Shop(
                        "SH01", "Shop 1", null, null, null, "Colombo", null, null, "en", "M", List.of(1))),
                List.of(),
                List.of());

        handler.handle(cmd, SCOPE);
        assertThat(BulkRegisterNetworkHandler.lastTelemetry()).isNull();

        // When diagnostics is enabled, handler handle() populates telemetry and consumeTelemetry() clears it
        System.setProperty("coop-erp.bulk-register.diagnostics", "true");
        try {
            handler.handle(cmd, SCOPE);
            BulkRegisterNetworkHandler.DiagnosticTelemetry telemetry = BulkRegisterNetworkHandler.lastTelemetry();
            assertThat(telemetry).isNotNull();
            assertThat(telemetry.ownerEntityId()).isEqualTo(ENTITY_ID);

            BulkRegisterNetworkHandler.DiagnosticTelemetry consumed = BulkRegisterNetworkHandler.consumeTelemetry();
            assertThat(consumed).isSameAs(telemetry);
            assertThat(BulkRegisterNetworkHandler.lastTelemetry()).isNull();
            assertThat(BulkRegisterNetworkHandler.consumeTelemetry()).isNull();
        } finally {
            System.clearProperty("coop-erp.bulk-register.diagnostics");
            BulkRegisterNetworkHandler.clearTelemetry();
        }
    }
}
