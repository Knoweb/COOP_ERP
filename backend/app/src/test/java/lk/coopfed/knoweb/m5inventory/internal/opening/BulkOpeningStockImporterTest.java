package lk.coopfed.knoweb.m5inventory.internal.opening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m5inventory.api.BulkImportOpeningStock;
import lk.coopfed.knoweb.m5inventory.api.BulkOpeningStockReport;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceChanged;
import lk.coopfed.knoweb.m5inventory.api.OpeningStockBulkImported;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BulkOpeningStockImporterTest {

    private static final UUID ENTITY = UUID.fromString("0190e679-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190e679-0000-7000-8000-000000000002");
    private static final UUID LOCATION = UUID.fromString("0190e679-0000-7000-8000-000000000003");
    private static final UUID SKU_ID = UUID.fromString("0190e679-0000-7000-8000-000000000004");
    private static final UUID BATCH_ID = UUID.fromString("0190e679-0000-7000-8000-000000000005");

    private OpeningBalanceStore store;
    private PartyQueries party;
    private CatalogueQueries catalogue;
    private BatchQueries batches;
    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private BulkOpeningStockImporter importer;

    private final ScopeContext scope = ScopeContext.dev(USER, ENTITY, null);

    @BeforeEach
    void setUp() {
        store = mock(OpeningBalanceStore.class);
        party = mock(PartyQueries.class);
        catalogue = mock(CatalogueQueries.class);
        batches = mock(BatchQueries.class);
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);

        importer = new BulkOpeningStockImporter(store, party, catalogue, batches, jdbc, audit, events);
    }

    @Test
    void requiresOwnScope() {
        Scope sc = new Scope(ENTITY, null);
        ScopeContext nonOwn = new ScopeContext(
                USER,
                null,
                ENTITY,
                List.of(sc),
                sc,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
        assertThatThrownBy(
                        () -> importer.handle(BulkImportOpeningStock.ofSheet(LOCATION, "sku,qty,cost\nS1,1,1"), nonOwn))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.scope.own_required"));
    }

    @Test
    void requiresLocationInScopeAndOnboarding() {
        when(party.getLocation(LOCATION, scope)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () -> importer.handle(BulkImportOpeningStock.ofSheet(LOCATION, "sku,qty,cost\nS1,1,1"), scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.location.not_in_scope"));

        LocationView activeLoc = mock(LocationView.class);
        when(activeLoc.ownerEntityId()).thenReturn(ENTITY);
        when(activeLoc.status()).thenReturn("ACTIVE");
        when(party.getLocation(LOCATION, scope)).thenReturn(Optional.of(activeLoc));
        assertThatThrownBy(
                        () -> importer.handle(BulkImportOpeningStock.ofSheet(LOCATION, "sku,qty,cost\nS1,1,1"), scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m5.opening.location_not_onboarding"));
    }

    @Test
    void refusesWhenLocationHasStockOrOpenBalance() {
        LocationView onboardingLoc = mock(LocationView.class);
        when(onboardingLoc.ownerEntityId()).thenReturn(ENTITY);
        when(onboardingLoc.status()).thenReturn("ONBOARDING");
        when(party.getLocation(LOCATION, scope)).thenReturn(Optional.of(onboardingLoc));

        when(store.locationHasMovements(LOCATION)).thenReturn(true);
        assertThatThrownBy(
                        () -> importer.handle(BulkImportOpeningStock.ofSheet(LOCATION, "sku,qty,cost\nS1,1,1"), scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m5.opening.location_has_stock"));

        when(store.locationHasMovements(LOCATION)).thenReturn(false);
        when(store.openBalanceAt(LOCATION)).thenReturn(true);
        assertThatThrownBy(
                        () -> importer.handle(BulkImportOpeningStock.ofSheet(LOCATION, "sku,qty,cost\nS1,1,1"), scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.opening.already_open"));
    }

    @Test
    void refusesEmptyLinesOrInvalidRow() {
        LocationView onboardingLoc = mock(LocationView.class);
        when(onboardingLoc.ownerEntityId()).thenReturn(ENTITY);
        when(onboardingLoc.status()).thenReturn("ONBOARDING");
        when(party.getLocation(LOCATION, scope)).thenReturn(Optional.of(onboardingLoc));
        when(store.locationHasMovements(LOCATION)).thenReturn(false);
        when(store.openBalanceAt(LOCATION)).thenReturn(false);

        assertThatThrownBy(() -> importer.handle(BulkImportOpeningStock.ofSheet(LOCATION, ""), scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.opening.lines_required"));

        // negative quantity
        assertThatThrownBy(() -> importer.handle(
                        BulkImportOpeningStock.ofSheet(LOCATION, "sku,qty,unit_cost\nS1,-5,100"), scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.opening.line_invalid"));

        // negative cost
        assertThatThrownBy(() ->
                        importer.handle(BulkImportOpeningStock.ofSheet(LOCATION, "sku,qty,unit_cost\nS1,5,-10"), scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.opening.line_invalid"));
    }

    @Test
    void parsesSheetAndCreatesOpeningBalanceAwaitingSignatures() {
        LocationView onboardingLoc = mock(LocationView.class);
        when(onboardingLoc.ownerEntityId()).thenReturn(ENTITY);
        when(onboardingLoc.status()).thenReturn("ONBOARDING");
        when(party.getLocation(LOCATION, scope)).thenReturn(Optional.of(onboardingLoc));
        when(store.locationHasMovements(LOCATION)).thenReturn(false);
        when(store.openBalanceAt(LOCATION)).thenReturn(false);

        // Mock SKU search for DHAL1
        SkuView dhalSku = new SkuView(
                SKU_ID,
                "DHAL1",
                ENTITY,
                "ACTIVE",
                "Red Dhal",
                null,
                null,
                null,
                null,
                null,
                "KG",
                false,
                true,
                true,
                true,
                (short) 30,
                null,
                null,
                null,
                null);
        when(catalogue.searchSku(any(SkuFilter.class), eq(scope))).thenReturn(new SkuPage(List.of(dhalSku), null));
        when(catalogue.getSku(SKU_ID, scope)).thenReturn(Optional.of(dhalSku));

        String csv =
                """
                sku,qty,unit_cost,batch_no,expiry_date,printed_mrp,condition
                DHAL1,25.000,180.5000,B101,2027-12-31,220.00,GOOD
                """;

        BulkOpeningStockReport report = importer.handle(BulkImportOpeningStock.ofSheet(LOCATION, csv), scope);

        assertThat(report.status()).isEqualTo(BulkOpeningStockReport.IMPORTED);
        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.importedLines()).isEqualTo(1);
        assertThat(report.openingBalanceId()).isNotNull();

        // Verify audit calls
        verify(audit).record(eq(PrepareOpeningBalanceHandler.AUDIT_PREPARED), any(), any(), any(), eq(scope));
        verify(audit).record(eq(BulkOpeningStockImporter.AUDIT_STOCK_BULK_IMPORTED), any(), any(), any(), eq(scope));

        // Verify domain events: OpeningBalanceChanged with DRAFT, and OpeningStockBulkImported
        verify(events).publish(any(OpeningBalanceChanged.class));
        verify(events).publish(any(OpeningStockBulkImported.class));
    }
}
