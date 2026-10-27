package lk.coopfed.knoweb.m4trading.internal.opening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m4trading.api.BulkImportReceivables;
import lk.coopfed.knoweb.m4trading.api.BulkReceivableReport;
import lk.coopfed.knoweb.m4trading.api.OpeningReceivableLoaded;
import lk.coopfed.knoweb.m4trading.api.ReceivableOpeningRow;
import lk.coopfed.knoweb.m4trading.api.ReceivablesBulkImported;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class BulkReceivableImporterTest {

    private static final UUID SELLER_ENTITY = UUID.fromString("0190e679-0000-7000-8000-000000000001");
    private static final UUID BUYER_ENTITY = UUID.fromString("0190e679-0000-7000-8000-000000000002");
    private static final UUID SELLER_USER = UUID.fromString("0190e679-0000-7000-8000-000000000003");
    private static final UUID BUYER_USER = UUID.fromString("0190e679-0000-7000-8000-000000000004");
    private static final UUID RELATIONSHIP_ID = UUID.fromString("0190e679-0000-7000-8000-000000000005");

    private JdbcTemplate jdbc;
    private DocumentBaseRepository documents;
    private DocumentIssuance issuance;
    private TradingSeries series;
    private PartyQueries parties;
    private RelationshipQueries relationships;
    private AuditFacade audit;
    private EventPublisher events;
    private TradingClock clock;
    private BulkReceivableImporter importer;

    private final ScopeContext scope = ScopeContext.dev(SELLER_USER, SELLER_ENTITY, null);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        documents = mock(DocumentBaseRepository.class);
        issuance = mock(DocumentIssuance.class);
        series = mock(TradingSeries.class);
        parties = mock(PartyQueries.class);
        relationships = mock(RelationshipQueries.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = mock(TradingClock.class);

        when(clock.today()).thenReturn(LocalDate.of(2026, 10, 27));
        when(clock.now()).thenReturn(Instant.parse("2026-10-27T10:00:00Z"));

        importer = new BulkReceivableImporter(
                jdbc, documents, issuance, series, parties, relationships, audit, events, clock);
    }

    @Test
    void requiresNonNullCommand() {
        assertThatThrownBy(() -> importer.handle(null, scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("request.invalid"));
    }

    @Test
    void requiresEntityScope() {
        ScopeContext locationScoped = ScopeContext.dev(SELLER_USER, SELLER_ENTITY, UUID.randomUUID());
        BulkImportReceivables cmd = new BulkImportReceivables(SELLER_ENTITY, null, List.of(), false);
        assertThatThrownBy(() -> importer.handle(cmd, locationScoped))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("scope.invalid"));
    }

    @Test
    void sellerMismatchRejectedForNonFederation() {
        UUID otherEntity = UUID.randomUUID();
        BulkImportReceivables cmd = new BulkImportReceivables(otherEntity, null, List.of(), false);
        when(jdbc.queryForObject(eq("select kernel.system_entity()"), eq(UUID.class)))
                .thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> importer.handle(cmd, scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("scope.invalid"));
    }

    @Test
    void requiresRows() {
        BulkImportReceivables cmd = new BulkImportReceivables(SELLER_ENTITY, "", List.of(), false);
        assertThatThrownBy(() -> importer.handle(cmd, scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m4.receivable.lines_required"));
    }

    @Test
    void buyerNotFound() {
        ReceivableOpeningRow row = new ReceivableOpeningRow(
                SELLER_ENTITY,
                null,
                null,
                null,
                new BigDecimal("100.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        BulkImportReceivables cmd = new BulkImportReceivables(SELLER_ENTITY, null, List.of(row), false);

        assertThatThrownBy(() -> importer.handle(cmd, scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m4.receivable.buyer_not_found"));
    }

    @Test
    void sameEntityRejected() {
        ReceivableOpeningRow row = new ReceivableOpeningRow(
                SELLER_ENTITY,
                null,
                SELLER_ENTITY,
                null,
                new BigDecimal("100.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        BulkImportReceivables cmd = new BulkImportReceivables(SELLER_ENTITY, null, List.of(row), false);

        assertThatThrownBy(() -> importer.handle(cmd, scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m4.receivable.same_entity"));
    }

    @Test
    void invalidAmountRejected() {
        ReceivableOpeningRow negativeRow = new ReceivableOpeningRow(
                SELLER_ENTITY,
                null,
                BUYER_ENTITY,
                null,
                new BigDecimal("-10.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        BulkImportReceivables cmd = new BulkImportReceivables(SELLER_ENTITY, null, List.of(negativeRow), false);

        assertThatThrownBy(() -> importer.handle(cmd, scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m4.receivable.amount_invalid"));

        ReceivableOpeningRow threeDecimalsRow = new ReceivableOpeningRow(
                SELLER_ENTITY,
                null,
                BUYER_ENTITY,
                null,
                new BigDecimal("10.125"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        BulkImportReceivables cmd2 = new BulkImportReceivables(SELLER_ENTITY, null, List.of(threeDecimalsRow), false);

        assertThatThrownBy(() -> importer.handle(cmd2, scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m4.receivable.amount_invalid"));
    }

    @Test
    void noRelationshipRejected() {
        ReceivableOpeningRow row = new ReceivableOpeningRow(
                SELLER_ENTITY,
                null,
                BUYER_ENTITY,
                null,
                new BigDecimal("100.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        BulkImportReceivables cmd = new BulkImportReceivables(SELLER_ENTITY, null, List.of(row), false);

        when(relationships.settlementRelationship(eq(SELLER_ENTITY), eq(BUYER_ENTITY), any(), any()))
                .thenReturn(Optional.empty());
        when(relationships.lookupRelationship(eq(SELLER_ENTITY), eq(BUYER_ENTITY), any(), any()))
                .thenReturn(Optional.empty());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(SELLER_ENTITY), eq(BUYER_ENTITY)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> importer.handle(cmd, scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m4.receivable.no_relationship"));
    }

    @Test
    void signaturesRequiredWhenNotSimulated() {
        ReceivableOpeningRow row = new ReceivableOpeningRow(
                SELLER_ENTITY,
                null,
                BUYER_ENTITY,
                null,
                new BigDecimal("100.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        BulkImportReceivables cmd = new BulkImportReceivables(SELLER_ENTITY, null, List.of(row), false);

        RelationshipView rel = mock(RelationshipView.class);
        when(rel.relationshipId()).thenReturn(RELATIONSHIP_ID);
        when(relationships.settlementRelationship(eq(SELLER_ENTITY), eq(BUYER_ENTITY), any(), any()))
                .thenReturn(Optional.of(rel));

        // Signer resolution returns 0 count / empty
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());

        assertThatThrownBy(() -> importer.handle(cmd, scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m4.receivable.buyer_signature_required"));
    }

    @Test
    void successfulImportWithSimulatedSignatures() {
        ReceivableOpeningRow row = new ReceivableOpeningRow(
                SELLER_ENTITY,
                null,
                BUYER_ENTITY,
                null,
                new BigDecimal("2500.50"),
                null,
                null,
                "REF-001",
                null,
                null,
                null,
                null,
                "Initial balance");
        BulkImportReceivables cmd = new BulkImportReceivables(SELLER_ENTITY, null, List.of(row), true);

        RelationshipView rel = mock(RelationshipView.class);
        when(rel.relationshipId()).thenReturn(RELATIONSHIP_ID);
        when(relationships.settlementRelationship(eq(SELLER_ENTITY), eq(BUYER_ENTITY), any(), any()))
                .thenReturn(Optional.of(rel));

        // Signer fallback and simulation
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(SELLER_USER), eq(SELLER_ENTITY)))
                .thenReturn(1);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(BUYER_ENTITY))).thenReturn(List.of(BUYER_USER));

        EntityView sellerView = mock(EntityView.class);
        when(sellerView.vatRegistrationNo()).thenReturn("VAT-SELLER");
        when(parties.getEntity(eq(SELLER_ENTITY), any())).thenReturn(Optional.of(sellerView));

        EntityView buyerView = mock(EntityView.class);
        when(buyerView.vatRegistrationNo()).thenReturn("VAT-BUYER");
        when(parties.getEntity(eq(BUYER_ENTITY), any())).thenReturn(Optional.of(buyerView));

        DocumentRecord draft = mock(DocumentRecord.class);
        when(documents.findByIdForUpdate(any())).thenReturn(Optional.of(draft));

        DocumentRecord issued = mock(DocumentRecord.class);
        when(issuance.issue(eq(draft), any(), any())).thenReturn(issued);

        BulkReceivableReport report = importer.handle(cmd, scope);

        assertThat(report).isNotNull();
        assertThat(report.status()).isEqualTo(BulkReceivableReport.IMPORTED);
        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.importedCount()).isEqualTo(1);
        assertThat(report.documentIds()).hasSize(1);
        assertThat(report.problems()).isEmpty();

        verify(documents).save(any());
        verify(documents).saveLines(any(), any());
        verify(issuance).issue(any(), any(), any());
        verify(audit).record(eq(BulkReceivableImporter.AUDIT_RECEIVABLE_IMPORTED), any(), any(), any(), any());
        verify(audit).record(eq(BulkReceivableImporter.AUDIT_BULK_IMPORTED), any(), any(), any(), any());
        verify(events).publish(any(OpeningReceivableLoaded.class));
        verify(events).publish(any(ReceivablesBulkImported.class));
    }
}
