package lk.coopfed.knoweb.m5inventory.internal.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.DepartmentCategoryView;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.internal.expiry.ExpiryAlertStore;
import lk.coopfed.knoweb.m5inventory.internal.reorder.ReorderStore;
import lk.coopfed.knoweb.m5inventory.internal.reprice.RepriceStore;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.StockExplanationView;
import lk.coopfed.knoweb.m5inventory.query.StockSummaryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

class StockSummaryQueryTest {

    private static final UUID ENTITY_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000002");
    private static final UUID SKU_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000003");
    private static final UUID DEPT_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000004");
    private static final UUID LOT_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000005");
    private static final UUID BATCH_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000006");
    private static final UUID PICK_LIST_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000007");
    private static final UUID TRANSFER_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000008");
    private static final UUID TASK_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000009");

    private JdbcTemplate jdbc;
    private BusinessDay businessDay;
    private ControlPolicy controlPolicy;
    private ReorderStore reorderStore;
    private RepriceStore repriceStore;
    private ExpiryAlertStore expiryAlertStore;
    private CatalogueQueries catalogueQueries;
    private PartyQueries partyQueries;
    private InventoryQueries queries;
    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        businessDay = mock(BusinessDay.class);
        when(businessDay.today()).thenReturn(LocalDate.of(2026, 11, 4));
        controlPolicy = mock(ControlPolicy.class);
        reorderStore = mock(ReorderStore.class);
        repriceStore = mock(RepriceStore.class);
        expiryAlertStore = mock(ExpiryAlertStore.class);
        catalogueQueries = mock(CatalogueQueries.class);
        partyQueries = mock(PartyQueries.class);

        queries = new InventoryQueriesImpl(
                jdbc,
                businessDay,
                controlPolicy,
                reorderStore,
                repriceStore,
                expiryAlertStore,
                catalogueQueries,
                partyQueries,
                "Asia/Colombo");

        scope = ScopeContext.dev(UUID.randomUUID(), ENTITY_ID, LOCATION_ID);
    }

    @Test
    void stockSummariesAggregatesLotsReservationsTransfersAndCounts() throws Exception {
        // Mock balances query (passes 5 arguments)
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    if (sql.contains("ranked")) {
                        return List.of(new LotBalance(
                                LOT_ID,
                                ENTITY_ID,
                                LOCATION_ID,
                                SKU_ID,
                                BATCH_ID,
                                LocalDate.parse("2027-12-31"),
                                "GOOD",
                                new BigDecimal("100.000"),
                                1,
                                new BigDecimal("100.0000"),
                                Instant.parse("2026-10-01T10:00:00Z"),
                                false,
                                false));
                    }
                    return List.of();
                });

        // Mock RowCallbackHandler query for reservations (2 args: locationId, today)
        doAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    RowCallbackHandler rch = invocation.getArgument(1, RowCallbackHandler.class);
                    ResultSet rs = mock(ResultSet.class);
                    if (sql.contains("pick_list_line")) {
                        when(rs.getObject("sku_id", UUID.class)).thenReturn(SKU_ID);
                        when(rs.getBigDecimal("reserved_qty")).thenReturn(new BigDecimal("15.000"));
                        rch.processRow(rs);
                    }
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), any(), any());

        // Mock RowCallbackHandler query for incoming/outgoing transfers (1 arg: locationId)
        doAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    RowCallbackHandler rch = invocation.getArgument(1, RowCallbackHandler.class);
                    ResultSet rs = mock(ResultSet.class);
                    if (sql.contains("to_location_id = ?")) {
                        when(rs.getObject("sku_id", UUID.class)).thenReturn(SKU_ID);
                        when(rs.getBigDecimal("incoming_qty")).thenReturn(new BigDecimal("40.000"));
                        rch.processRow(rs);
                    } else if (sql.contains("t.location_id = ?") && sql.contains("outgoing_qty")) {
                        when(rs.getObject("sku_id", UUID.class)).thenReturn(SKU_ID);
                        when(rs.getBigDecimal("outgoing_qty")).thenReturn(new BigDecimal("10.000"));
                        rch.processRow(rs);
                    }
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), any());

        // Mock queryForList for count task and count lines (1 UUID arg)
        when(jdbc.queryForList(anyString(), any(UUID.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("from inventory.count_task")) {
                return List.of(Map.of(
                        "task_id",
                        TASK_ID,
                        "status",
                        "COMPLETED",
                        "outcome",
                        "DISCREPANT",
                        "submitted_at",
                        Timestamp.from(Instant.parse("2026-11-03T16:00:00Z"))));
            } else if (sql.contains("from inventory.count_line")) {
                return List.of(Map.of(
                        "sku_id", SKU_ID,
                        "var_qty", new BigDecimal("-2.000"),
                        "var_val", new BigDecimal("-440.00")));
            }
            return List.of();
        });

        SkuView skuView = new SkuView(
                SKU_ID,
                "SKU-RICE-01",
                ENTITY_ID,
                "ACTIVE",
                "White Rice 5kg",
                "සුදු කැකුළු 5kg",
                "வெள்ளை அரிசி 5kg",
                "5kg rice pack",
                null,
                null,
                "KG",
                false,
                true,
                true,
                true,
                (short) 30,
                null,
                "SINGLE",
                "DOMESTIC",
                Map.of(),
                DEPT_ID);
        when(catalogueQueries.getSku(SKU_ID, scope)).thenReturn(Optional.of(skuView));

        DepartmentCategoryView deptView = new DepartmentCategoryView(
                DEPT_ID, "STAPLES", "Staples", "මූලික ආහාර", "அடிப்படை உணவு", "Daily essentials", 1, "ACTIVE");
        when(catalogueQueries.departmentCategories(scope)).thenReturn(List.of(deptView));

        List<StockSummaryView> summaries = queries.stockSummaries(LOCATION_ID, null, null, null, true, false, scope);

        assertThat(summaries).hasSize(1);
        StockSummaryView summary = summaries.get(0);
        assertThat(summary.skuId()).isEqualTo(SKU_ID);
        assertThat(summary.skuCode()).isEqualTo("SKU-RICE-01");
        assertThat(summary.skuNameEn()).isEqualTo("White Rice 5kg");
        assertThat(summary.baseUomCode()).isEqualTo("KG");
        assertThat(summary.departmentNameEn()).isEqualTo("Staples");
        assertThat(summary.qtyOnHand()).isEqualByComparingTo("100.000");
        assertThat(summary.reservedQty()).isEqualByComparingTo("15.000");
        assertThat(summary.availableQty()).isEqualByComparingTo("85.000");
        assertThat(summary.inTransitIncomingQty()).isEqualByComparingTo("40.000");
        assertThat(summary.inTransitOutgoingQty()).isEqualByComparingTo("10.000");
        assertThat(summary.countedVarianceQty()).isEqualByComparingTo("-2.000");
        assertThat(summary.latestCountStatus()).isEqualTo("DISCREPANT");
        // Remaining = available (85) + incoming (40) = 125
        assertThat(summary.remainingStockQty()).isEqualByComparingTo("125.000");
    }

    @Test
    void stockExplanationReturnsBreakdownDetails() {
        SkuView skuView = new SkuView(
                SKU_ID,
                "SKU-RICE-01",
                ENTITY_ID,
                "ACTIVE",
                "White Rice 5kg",
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
                "SINGLE",
                "DOMESTIC",
                Map.of(),
                DEPT_ID);
        when(catalogueQueries.getSku(SKU_ID, scope)).thenReturn(Optional.of(skuView));
        when(catalogueQueries.departmentCategories(scope)).thenReturn(List.of());

        when(partyQueries.getLocation(any(UUID.class), any()))
                .thenReturn(Optional.of(new LocationView(
                        UUID.randomUUID(),
                        ENTITY_ID,
                        "HUB-01",
                        "SHOP",
                        "Central Hub",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "en",
                        List.of(),
                        "MEDIUM",
                        true,
                        null,
                        "ACTIVE")));

        // Setup mock query for lots (5 args)
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    if (sql.contains("ranked")) {
                        return List.of(new LotBalance(
                                LOT_ID,
                                ENTITY_ID,
                                LOCATION_ID,
                                SKU_ID,
                                BATCH_ID,
                                LocalDate.parse("2027-12-31"),
                                "GOOD",
                                new BigDecimal("50.000"),
                                1,
                                new BigDecimal("120.0000"),
                                Instant.parse("2026-10-01T10:00:00Z"),
                                false,
                                false));
                    }
                    return List.of();
                });

        // Setup mock query for reservations (3 args)
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("pick_list_line")) {
                return List.of(new StockExplanationView.ReservationDetail(
                        PICK_LIST_ID,
                        UUID.randomUUID(),
                        "DN-2026-01",
                        new BigDecimal("10.000"),
                        "OPEN",
                        Instant.parse("2026-11-04T08:00:00Z")));
            }
            return List.of();
        });

        // Setup mock query for transfers (2 args)
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("to_location_id = ?")) {
                return List.of(new StockExplanationView.InTransitDetail(
                        TRANSFER_ID,
                        "TR-001",
                        UUID.randomUUID(),
                        "Central Hub",
                        new BigDecimal("25.000"),
                        Instant.parse("2026-11-03T12:00:00Z"),
                        "DISPATCHED"));
            } else if (sql.contains("t.location_id = ?")) {
                return List.of();
            }
            return List.of();
        });

        // Setup mock query for count task (4 args)
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    if (sql.contains("count_task")) {
                        return List.of(new StockExplanationView.CountDetail(
                                TASK_ID,
                                "ALL",
                                "COMPLETED",
                                "MATCHED",
                                new BigDecimal("50.000"),
                                new BigDecimal("50.000"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                Instant.parse("2026-11-02T08:00:00Z"),
                                Instant.parse("2026-11-02T12:00:00Z")));
                    }
                    return List.of();
                });

        Optional<StockExplanationView> explanationOpt = queries.stockExplanation(LOCATION_ID, SKU_ID, scope);

        assertThat(explanationOpt).isPresent();
        StockExplanationView explanation = explanationOpt.get();
        assertThat(explanation.skuId()).isEqualTo(SKU_ID);
        assertThat(explanation.skuCode()).isEqualTo("SKU-RICE-01");
        assertThat(explanation.baseUomCode()).isEqualTo("KG");
        assertThat(explanation.qtyOnHand()).isEqualByComparingTo("50.000");
        assertThat(explanation.reservedQty()).isEqualByComparingTo("10.000");
        assertThat(explanation.availableQty()).isEqualByComparingTo("40.000");
        assertThat(explanation.inTransitIncomingQty()).isEqualByComparingTo("25.000");
        assertThat(explanation.inTransitOutgoingQty()).isEqualByComparingTo("0.000");
        assertThat(explanation.lots()).hasSize(1);
        assertThat(explanation.reservations()).hasSize(1);
        assertThat(explanation.incomingTransfers()).hasSize(1);
        assertThat(explanation.outgoingTransfers()).isEmpty();
        assertThat(explanation.latestCount()).isNotNull();
        assertThat(explanation.latestCount().outcome()).isEqualTo("MATCHED");
    }

    @Test
    void stockAvailabilityExcludesExpiredDamagedAndNegativeLots() {
        // Lot 1: GOOD, in-date, positive: 50.000
        // Lot 2: GOOD, expired: 20.000
        // Lot 3: DAMAGED, in-date: 15.000
        // Lot 4: GOOD, negative: -5.000
        // Total on-hand = 50 + 20 + 15 - 5 = 80.000
        // Good in-date positive = 50.000
        // Reserved = 10.000 -> Available = 40.000
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> List.of(
                        new LotBalance(
                                UUID.randomUUID(),
                                ENTITY_ID,
                                LOCATION_ID,
                                SKU_ID,
                                BATCH_ID,
                                LocalDate.parse("2027-12-31"),
                                "GOOD",
                                new BigDecimal("50.000"),
                                1,
                                new BigDecimal("100.0000"),
                                Instant.parse("2026-10-01T10:00:00Z"),
                                false,
                                false),
                        new LotBalance(
                                UUID.randomUUID(),
                                ENTITY_ID,
                                LOCATION_ID,
                                SKU_ID,
                                BATCH_ID,
                                LocalDate.parse("2026-10-01"),
                                "GOOD",
                                new BigDecimal("20.000"),
                                null,
                                new BigDecimal("100.0000"),
                                Instant.parse("2026-09-01T10:00:00Z"),
                                false,
                                true),
                        new LotBalance(
                                UUID.randomUUID(),
                                ENTITY_ID,
                                LOCATION_ID,
                                SKU_ID,
                                BATCH_ID,
                                LocalDate.parse("2027-12-31"),
                                "DAMAGED",
                                new BigDecimal("15.000"),
                                null,
                                new BigDecimal("100.0000"),
                                Instant.parse("2026-10-01T10:00:00Z"),
                                false,
                                false),
                        new LotBalance(
                                UUID.randomUUID(),
                                ENTITY_ID,
                                LOCATION_ID,
                                SKU_ID,
                                BATCH_ID,
                                LocalDate.parse("2027-12-31"),
                                "GOOD",
                                new BigDecimal("-5.000"),
                                null,
                                new BigDecimal("100.0000"),
                                Instant.parse("2026-10-01T10:00:00Z"),
                                true,
                                false)));

        doAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    RowCallbackHandler rch = invocation.getArgument(1, RowCallbackHandler.class);
                    ResultSet rs = mock(ResultSet.class);
                    if (sql.contains("pick_list_line")) {
                        when(rs.getObject("sku_id", UUID.class)).thenReturn(SKU_ID);
                        when(rs.getBigDecimal("reserved_qty")).thenReturn(new BigDecimal("10.000"));
                        rch.processRow(rs);
                    }
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), any(), any());

        when(jdbc.queryForList(anyString(), any(UUID.class))).thenReturn(List.of());

        SkuView skuView = new SkuView(
                SKU_ID,
                "SKU-RICE-01",
                ENTITY_ID,
                "ACTIVE",
                "White Rice 5kg",
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
                "SINGLE",
                "DOMESTIC",
                Map.of(),
                DEPT_ID);
        when(catalogueQueries.getSku(SKU_ID, scope)).thenReturn(Optional.of(skuView));
        when(catalogueQueries.departmentCategories(scope)).thenReturn(List.of());

        List<StockSummaryView> summaries = queries.stockSummaries(LOCATION_ID, null, null, null, true, false, scope);

        assertThat(summaries).hasSize(1);
        StockSummaryView summary = summaries.get(0);
        // Physical on hand includes all lots = 50 + 20 + 15 - 5 = 80.000
        assertThat(summary.qtyOnHand()).isEqualByComparingTo("80.000");
        assertThat(summary.reservedQty()).isEqualByComparingTo("10.000");
        // Available strictly follows availability semantics: only GOOD, in-date, positive (50) - reserved (10) = 40.000
        assertThat(summary.availableQty()).isEqualByComparingTo("40.000");
    }
}
