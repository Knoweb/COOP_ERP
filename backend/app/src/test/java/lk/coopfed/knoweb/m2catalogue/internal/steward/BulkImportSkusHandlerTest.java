package lk.coopfed.knoweb.m2catalogue.internal.steward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BulkImportSkus;
import lk.coopfed.knoweb.m2catalogue.api.ImportReport;
import lk.coopfed.knoweb.m2catalogue.api.ImportSkuRow;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuCodeGenerator;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.ItemProfile;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.SuspectMatch;
import lk.coopfed.knoweb.m2catalogue.internal.steward.RowValidator.ValidationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

class BulkImportSkusHandlerTest {

    private JdbcTemplate jdbc;
    private RowValidator validator;
    private Transliterator transliterator;
    private DuplicateScorer scorer;
    private SkuCodeGenerator codeGenerator;
    private AuditFacade audit;
    private EventPublisher events;
    private BulkImportSkusHandler handler;

    private static final UUID ENTITY = UUID.fromString("0190e620-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e620-0000-7000-8000-000000000010");
    private static final UUID TAX_CAT_ID = UUID.fromString("0190e620-0000-7000-8000-000000000100");
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
        validator = mock(RowValidator.class);
        transliterator = new Transliterator();
        scorer = mock(DuplicateScorer.class);
        codeGenerator = mock(SkuCodeGenerator.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);

        when(codeGenerator.next()).thenReturn("SKU-GEN001", "SKU-GEN002", "SKU-GEN003");

        // Mock preload queries
        when(jdbc.query(eq("SELECT uom_code FROM catalogue.uom"), any(RowMapper.class)))
                .thenReturn(List.of("EA", "KG"));

        // Tax category query
        org.mockito.Mockito.doAnswer(invocation -> {
                    RowCallbackHandler rch = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getString("code")).thenReturn("STD");
                    when(rs.getObject("tax_category_id")).thenReturn(TAX_CAT_ID);
                    rch.processRow(rs);
                    return null;
                })
                .when(jdbc)
                .query(eq("SELECT tax_category_id, code FROM catalogue.tax_category"), any(RowCallbackHandler.class));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(ENTITY))).thenReturn(List.of());

        handler = new BulkImportSkusHandler(jdbc, validator, transliterator, scorer, codeGenerator, audit, events);
    }

    @Test
    void rejectsMissingScope() {
        BulkImportSkus cmd = new BulkImportSkus("VALIDATE", List.of());
        assertThatThrownBy(() -> handler.handle(cmd, null))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m2.sku.scope_required");
    }

    @Test
    void returnsRejectedReportWhenPayloadIsEmpty() {
        BulkImportSkus cmd = new BulkImportSkus("VALIDATE", List.of());
        ImportReport report = handler.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(ImportReport.STATUS_REJECTED);
        assertThat(report.errors()).anyMatch(e -> "bulk.sku.empty_payload".equals(e.code()));
        verify(jdbc, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    @Test
    void validateModeDoesNotExecuteBatchInsertsOrPublishEvents() {
        ImportSkuRow row1 = new ImportSkuRow(
                1,
                null,
                "White Sugar 1kg",
                null,
                null,
                null,
                null,
                null,
                "KG",
                true,
                false,
                false,
                false,
                null,
                "STD",
                new BigDecimal("320.00"),
                List.of("4791000000003"),
                "cargills",
                List.of(),
                Map.of());

        when(validator.validateBatch(anyList(), any(ValidationContext.class))).thenReturn(List.of());
        when(scorer.scoreWithinBatch(anyList(), eq(0.85))).thenReturn(List.of());

        BulkImportSkus cmd = new BulkImportSkus("VALIDATE", List.of(row1));
        ImportReport report = handler.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(ImportReport.STATUS_VALIDATED);
        assertThat(report.accepted()).isEqualTo(1);
        assertThat(report.rejected()).isZero();

        // Ensure no mutations, audit or events in VALIDATE mode
        verify(jdbc, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        verify(audit, never()).record(anyString(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void commitModeExecutesBatchInsertsRecordsAuditAndPublishesEvents() {
        ImportSkuRow row1 = new ImportSkuRow(
                1,
                "SKU-SUGAR",
                "White Sugar 1kg",
                null,
                null,
                null,
                null,
                null,
                "KG",
                true,
                false,
                false,
                false,
                null,
                "STD",
                new BigDecimal("320.00"),
                List.of("4791000000003"),
                "cargills",
                List.of(),
                Map.of());

        when(validator.validateBatch(anyList(), any(ValidationContext.class))).thenReturn(List.of());

        ItemProfile candidate = new ItemProfile(
                null, "SKU-SUGAR", "White Sugar 1kg", null, null, "KG", "cargills", List.of("4791000000003"));
        UUID existingId = UUID.randomUUID();
        ItemProfile existing = new ItemProfile(
                existingId,
                "SKU-OLD",
                "Cargills White Sugar 1kg",
                null,
                null,
                "KG",
                "cargills",
                List.of("4791000000003"));

        SuspectMatch suspectMatch = new SuspectMatch(
                candidate, existing, new BigDecimal("0.920"), List.of(DuplicateScorer.REASON_NAME_SIMILARITY));

        when(scorer.score(any(), any(), eq(0.85)))
                .thenReturn(new DuplicateScorer.ScoreResult(
                        new BigDecimal("0.920"), true, List.of(DuplicateScorer.REASON_NAME_SIMILARITY)));

        BulkImportSkus cmd = new BulkImportSkus("COMMIT", List.of(row1));
        ImportReport report = handler.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(ImportReport.STATUS_ACCEPTED);
        assertThat(report.accepted()).isEqualTo(1);

        // Verify batch updates were executed (for SKUs and Barcodes)
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> bpssCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).batchUpdate(sqlCaptor.capture(), bpssCaptor.capture());

        boolean foundSkuInsert = false;
        for (int i = 0; i < sqlCaptor.getAllValues().size(); i++) {
            String sql = sqlCaptor.getAllValues().get(i);
            BatchPreparedStatementSetter bpss = bpssCaptor.getAllValues().get(i);
            if (sql.contains("catalogue.sku (")) {
                foundSkuInsert = true;
                long qmarks = sql.chars().filter(ch -> ch == '?').count();
                assertThat(qmarks).isEqualTo(16);
                java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
                try {
                    bpss.setValues(ps, 0);
                    verify(ps).setObject(eq(16), eq(TAX_CAT_ID));
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        assertThat(foundSkuInsert).isTrue();

        // Verify audit recorded
        verify(audit).record(eq(BulkImportSkusHandler.AUDIT_BULK_IMPORTED), any(), any(), any(), eq(SCOPE));

        // Verify domain events published
        verify(events, org.mockito.Mockito.atLeastOnce()).publish(any());
    }
}
