package lk.coopfed.knoweb.m2catalogue.internal.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BulkImportSuppliers;
import lk.coopfed.knoweb.m2catalogue.api.SupplierImportReport;
import lk.coopfed.knoweb.m2catalogue.api.SupplierImportRow;
import lk.coopfed.knoweb.m2catalogue.api.SuppliersBulkImported;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

@DisplayName("Bulk Import Suppliers Handler")
class BulkImportSuppliersHandlerTest {

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private SupplierCsvParser parser;
    private BulkImportSuppliersHandler handler;

    private static final UUID ENTITY_ID = UUID.fromString("0190e620-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("0190e620-0000-7000-8000-000000000010");
    private static final ScopeContext SCOPE = new ScopeContext(
            USER_ID,
            null,
            ENTITY_ID,
            List.of(new lk.coopfed.knoweb.kernel.api.Scope(ENTITY_ID, null)),
            new lk.coopfed.knoweb.kernel.api.Scope(ENTITY_ID, null),
            PolicyClass.OWN,
            Set.of(),
            null,
            java.util.Locale.ENGLISH,
            null);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        parser = new SupplierCsvParser();
        handler = new BulkImportSuppliersHandler(jdbc, parser, audit, events);
    }

    @Test
    @DisplayName("VALIDATE mode performs dry run with 0 database writes, 0 audits, and 0 published events")
    void validateModePerformsDryRunWithZeroWritesAuditsOrEvents() {
        List<SupplierImportRow> rows = List.of(
                new SupplierImportRow(1, "Ceylon Tea Board", "SUP-CTB"),
                new SupplierImportRow(2, "Lanka Sugar Company", "SUP-LSC"));

        BulkImportSuppliers command = new BulkImportSuppliers(BulkImportSuppliers.MODE_VALIDATE, null, rows);

        SupplierImportReport report = handler.handle(command, SCOPE);

        assertThat(report.status()).isEqualTo(SupplierImportReport.STATUS_VALIDATED);
        assertThat(report.totalRows()).isEqualTo(2);
        assertThat(report.createdCount()).isEqualTo(2);
        assertThat(report.skippedCount()).isEqualTo(0);
        assertThat(report.rejectedCount()).isEqualTo(0);
        assertThat(report.errors()).isEmpty();

        verify(jdbc, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("COMMIT mode inserts new suppliers and publishes both audit and domain event")
    void commitModeInsertsNewSuppliersAndPublishesAuditAndEvent() {
        List<SupplierImportRow> rows = List.of(
                new SupplierImportRow(1, "Ceylon Tea Board", "SUP-CTB"),
                new SupplierImportRow(2, "Lanka Sugar Company", "SUP-LSC"));

        BulkImportSuppliers command = new BulkImportSuppliers(BulkImportSuppliers.MODE_COMMIT, null, rows);

        SupplierImportReport report = handler.handle(command, SCOPE);

        assertThat(report.status()).isEqualTo(SupplierImportReport.STATUS_ACCEPTED);
        assertThat(report.totalRows()).isEqualTo(2);
        assertThat(report.createdCount()).isEqualTo(2);
        assertThat(report.skippedCount()).isEqualTo(0);
        assertThat(report.rejectedCount()).isEqualTo(0);
        assertThat(report.errors()).isEmpty();

        verify(jdbc).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        verify(audit)
                .record(
                        eq(BulkImportSuppliersHandler.AUDIT_BULK_IMPORTED),
                        any(Subject.class),
                        eq(null),
                        any(Map.class),
                        eq(SCOPE));

        ArgumentCaptor<SuppliersBulkImported> eventCaptor = ArgumentCaptor.forClass(SuppliersBulkImported.class);
        verify(events).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().ownerEntityId()).isEqualTo(ENTITY_ID);
        assertThat(eventCaptor.getValue().createdCount()).isEqualTo(2);
        assertThat(eventCaptor.getValue().skippedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName(
            "COMMIT mode idempotently skips identical existing suppliers without duplicate database writes or events")
    void commitModeSkipsIdenticalExistingSuppliers() {
        // Mock existing supplier in database
        doAnswer(invocation -> {
                    RowCallbackHandler rch = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("supplier_id")).thenReturn(UUID.randomUUID());
                    when(rs.getString("name")).thenReturn("Ceylon Tea Board");
                    when(rs.getString("supplier_code")).thenReturn("SUP-CTB");
                    when(rs.getString("status")).thenReturn("ACTIVE");
                    rch.processRow(rs);
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), eq(ENTITY_ID));

        List<SupplierImportRow> rows = List.of(new SupplierImportRow(1, "Ceylon Tea Board", "SUP-CTB"));

        BulkImportSuppliers command = new BulkImportSuppliers(BulkImportSuppliers.MODE_COMMIT, null, rows);

        SupplierImportReport report = handler.handle(command, SCOPE);

        assertThat(report.status()).isEqualTo(SupplierImportReport.STATUS_ACCEPTED);
        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.createdCount()).isEqualTo(0);
        assertThat(report.skippedCount()).isEqualTo(1);
        assertThat(report.rejectedCount()).isEqualTo(0);

        // No new rows inserted into database
        verify(jdbc, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        // Audit and event record that 0 were created and 1 was skipped
        verify(audit)
                .record(
                        eq(BulkImportSuppliersHandler.AUDIT_BULK_IMPORTED),
                        any(Subject.class),
                        eq(null),
                        any(Map.class),
                        eq(SCOPE));

        // When createdCount is 0, no duplicate domain event is published
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("rejects in-sheet collisions where the same supplier code maps to different names")
    void rejectsInSheetDuplicateCodeCollision() {
        List<SupplierImportRow> rows = List.of(
                new SupplierImportRow(1, "Ceylon Tea Board", "SUP-001"),
                new SupplierImportRow(2, "Different Tea Vendor", "SUP-001"));

        BulkImportSuppliers command = new BulkImportSuppliers(BulkImportSuppliers.MODE_VALIDATE, null, rows);

        SupplierImportReport report = handler.handle(command, SCOPE);

        assertThat(report.status()).isEqualTo(SupplierImportReport.STATUS_REJECTED);
        assertThat(report.rejectedCount()).isEqualTo(1);
        assertThat(report.errors()).anyMatch(e -> "m2.supplier.code_conflict_in_sheet".equals(e.code()));
    }

    @Test
    @DisplayName("rejects database collisions where the supplier code belongs to a different supplier name")
    void rejectsDatabaseCodeCollision() {
        // Mock existing supplier in DB
        doAnswer(invocation -> {
                    RowCallbackHandler rch = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("supplier_id")).thenReturn(UUID.randomUUID());
                    when(rs.getString("name")).thenReturn("Ceylon Tea Board");
                    when(rs.getString("supplier_code")).thenReturn("SUP-001");
                    when(rs.getString("status")).thenReturn("ACTIVE");
                    rch.processRow(rs);
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), eq(ENTITY_ID));

        List<SupplierImportRow> rows = List.of(new SupplierImportRow(1, "Fresh Agro Suppliers", "SUP-001"));

        BulkImportSuppliers command = new BulkImportSuppliers(BulkImportSuppliers.MODE_VALIDATE, null, rows);

        SupplierImportReport report = handler.handle(command, SCOPE);

        assertThat(report.status()).isEqualTo(SupplierImportReport.STATUS_REJECTED);
        assertThat(report.rejectedCount()).isEqualTo(1);
        assertThat(report.errors()).anyMatch(e -> "m2.supplier.code_conflict".equals(e.code()));
    }
}
