package lk.coopfed.knoweb.m5inventory.internal.transfer;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssued;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.CancelTransfer;
import lk.coopfed.knoweb.m5inventory.api.DispatchTransfer;
import lk.coopfed.knoweb.m5inventory.api.IssueTransfer;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.ReceiveTransfer;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.TransferCancelled;
import lk.coopfed.knoweb.m5inventory.api.TransferDispatched;
import lk.coopfed.knoweb.m5inventory.api.TransferIssued;
import lk.coopfed.knoweb.m5inventory.api.TransferReceived;
import lk.coopfed.knoweb.m5inventory.api.TransferShortfallDrafted;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.TransferView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The internal transfer (25A M5-09 at demo scope; doc 25 flow 6.6): a society's warehouse sends
 * stock to one of its shops; the stock leaves the warehouse as TRANSFER_OUT, is in transit, and
 * the shop's own session receives it as TRANSFER_IN at the cost it left with. Each side writes
 * only its own rows (PR #148): the shop session writes the receipt and its movements at the shop
 * and nothing at the warehouse. Every guard with nothing committed.
 */
class TransferPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67a-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("0190e67a-0000-7000-8000-000000000003");

    @Autowired
    IssueTransferHandler issue;

    @Autowired
    DispatchTransferHandler dispatch;

    @Autowired
    ReceiveTransferHandler receive;

    @Autowired
    CancelTransferHandler cancel;

    @Autowired
    InventoryQueries queries;

    @Autowired
    StockLedger ledger;

    @Autowired
    DocumentBaseRepository documents;

    @Autowired
    NumberingService numbering;

    @MockitoSpyBean
    TransferDocumentType transferDocumentType;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID warehouse;
    private UUID shop;
    private UUID otherShop;
    private UUID batch;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5XF", "MPCS");
        warehouse = fixture.location(MPCS, "WAREHOUSE");
        shop = fixture.location(MPCS, "SHOP");
        otherShop = fixture.location(MPCS, "SHOP");
        UUID sku = fixture.sku(MPCS, "RICE5");
        batch = fixture.batch(sku, MPCS, "R1", LocalDate.of(2027, 3, 31));
        ScopeContext scope = own(MPCS);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        warehouse,
                                        batch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal("50"),
                                        new BigDecimal("1200"),
                                        null))),
                        scope));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        reset(transferDocumentType);
        fixture.clean();
    }

    @Test
    void theWarehouseSendsAndTheShopReceivesEachWritingItsOwnRows() {
        UUID id = issue.handle(transfer(warehouse, shop, "20"), own(MPCS));

        assertThat(qty(warehouse)).isEqualByComparingTo("30");
        assertThat(queries.balances(shop, null, false, own(MPCS))).isEmpty();
        TransferView sent = queries.transfer(id, own(MPCS)).orElseThrow();
        assertThat(sent.status()).isEqualTo("ISSUED");
        assertThat(sent.documentNo()).startsWith("M5XF-XFR-");
        assertThat(sent.lines()).singleElement().satisfies(l -> {
            assertThat(l.qty()).isEqualByComparingTo("20");
            assertThat(l.unitCost()).isEqualByComparingTo("1200");
        });
        assertThat(queries.movementsOf(id, own(MPCS))).singleElement().satisfies(m -> assertThat(m.movementType())
                .isEqualTo("TRANSFER_OUT"));
        DocumentRecord document =
                outer.run(own(MPCS), () -> documents.findById(id).orElseThrow());
        assertThat(document.id()).isEqualTo(id);
        assertThat(document.docTypeCode()).isEqualTo("XFR");
        assertThat(document.locationId()).isEqualTo(warehouse);
        assertThat(document.docNumberDisplay()).isEqualTo(sent.documentNo());
        assertThat(document.contentHash()).isNotBlank();
        List<DocumentLineRecord> documentLines = outer.run(own(MPCS), () -> documents.findLines(id));
        assertThat(documentLines).singleElement().satisfies(line -> {
            UUID transferLineId = superuserJdbc()
                    .queryForObject(
                            "select line_id from inventory.transfer_line where transfer_id = ?", UUID.class, id);
            assertThat(line.id()).isEqualTo(transferLineId);
            assertThat(line.documentId()).isEqualTo(id);
            assertThat(line.lineNo()).isEqualTo(1);
            assertThat(line.skuId()).isEqualTo(sent.lines().get(0).skuId());
            assertThat(line.batchId()).isEqualTo(batch);
            assertThat(line.qty()).isEqualByComparingTo("20");
            assertThat(line.unitCostAtIssue()).isEqualByComparingTo("1200");
            assertThat(line.lineTotal()).isEqualByComparingTo("24000");
        });
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_OUT'",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select unit_cost_at_movement from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_OUT'",
                                BigDecimal.class,
                                id))
                .isEqualByComparingTo(documentLines.get(0).unitCostAtIssue());
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "DOCUMENT_ISSUED", "TRANSFER_ISSUED");
        assertThat(events(DocumentIssued.class)).singleElement().satisfies(e -> {
            assertThat(e.documentId()).isEqualTo(id);
            assertThat(e.docTypeCode()).isEqualTo("XFR");
        });
        assertThat(events(TransferIssued.class)).singleElement().satisfies(e -> {
            assertThat(e.fromLocationId()).isEqualTo(warehouse);
            assertThat(e.toLocationId()).isEqualTo(shop);
        });

        // The shop's own session sees the transfer addressed to it, and not the warehouse's stock.
        ScopeContext shopSession = at(MPCS, shop);
        assertThat(queries.transfers(shop, shopSession)).singleElement().satisfies(t -> assertThat(t.status())
                .isEqualTo("ISSUED"));
        assertThat(queries.balances(warehouse, null, false, shopSession)).isEmpty();

        kernel.reset();
        dispatch.handle(new DispatchTransfer(id), own(MPCS));

        assertThat(queries.transfer(id, own(MPCS)).orElseThrow()).satisfies(t -> {
            assertThat(t.status()).isEqualTo("IN_TRANSIT");
            assertThat(t.dispatchedBy()).isEqualTo(InventoryFixture.USER);
            assertThat(t.dispatchedAt()).isNotNull();
        });
        assertThat(queries.movementsOf(id, own(MPCS))).singleElement().satisfies(m -> assertThat(m.movementType())
                .isEqualTo("TRANSFER_OUT"));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TRANSFER_DISPATCHED");
        assertThat(events(TransferDispatched.class)).singleElement().satisfies(e -> {
            assertThat(e.transferId()).isEqualTo(id);
            assertThat(e.fromLocationId()).isEqualTo(warehouse);
            assertThat(e.toLocationId()).isEqualTo(shop);
            assertThat(e.lines()).isEqualTo(1);
        });

        kernel.reset();
        receive.handle(new ReceiveTransfer(id), shopSession);

        assertThat(qty(shop)).isEqualByComparingTo("20");
        assertThat(qty(warehouse)).isEqualByComparingTo("30");
        assertThat(queries.balances(shop, null, false, shopSession))
                .singleElement()
                .satisfies(l -> assertThat(l.unitCost()).isEqualByComparingTo("1200"));
        assertThat(queries.entityAverageCost(heldSku(), own(MPCS))).hasValueSatisfying(c -> {
            assertThat(c.avgCost()).isEqualByComparingTo("1200");
            assertThat(c.qtyOnHand()).isEqualByComparingTo("50");
        });
        assertThat(queries.transfer(id, own(MPCS)).orElseThrow()).satisfies(t -> {
            assertThat(t.status()).isEqualTo("RECEIVED");
            assertThat(t.receivedBy()).isEqualTo(InventoryFixture.USER);
        });
        // The warehouse sees it received, through the receipt row the shop wrote.
        assertThat(queries.transfer(id, at(MPCS, warehouse)).orElseThrow().status())
                .isEqualTo("RECEIVED");
        // Every row the shop's session wrote is the shop's: the receipt and the TRANSFER_IN.
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select location_id from inventory.transfer_receipt where transfer_id = ?",
                                UUID.class,
                                id))
                .isEqualTo(shop);
        assertThat(superuserJdbc()
                        .queryForMap(
                                "select transfer_line_id, owner_entity_id, location_id, from_location_id,"
                                        + " received_qty from inventory.transfer_receipt_line where transfer_id = ?",
                                id))
                .containsEntry("transfer_line_id", documentLines.get(0).id())
                .containsEntry("owner_entity_id", MPCS)
                .containsEntry("location_id", shop)
                .containsEntry("from_location_id", warehouse)
                .containsEntry("received_qty", new BigDecimal("20.000"));
        assertThat(superuserJdbc()
                        .queryForList(
                                "select distinct location_id from inventory.stock_movement where document_id = ?"
                                        + " and movement_type = 'TRANSFER_IN'",
                                UUID.class,
                                id))
                .containsExactly(shop);
        // wave 2, M5-16: the same person issued and received it; allowed, and flagged.
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "TRANSFER_RECEIVED", "TRANSFER_SELF_RECEIVED");
        assertThat(events(TransferReceived.class)).singleElement().satisfies(e -> assertThat(e.toLocationId())
                .isEqualTo(shop));
    }

    @Test
    void aTransferReceivedByAnotherPersonIsNotFlagged() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        dispatch.handle(new DispatchTransfer(id), own(MPCS));
        kernel.reset();

        receive.handle(new ReceiveTransfer(id), own(MPCS, Ids.next()));

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("TRANSFER_RECEIVED")
                .doesNotContain("TRANSFER_SELF_RECEIVED");
    }

    @Test
    void aShopSourceUsesItsLocationXfrSeries() {
        UUID sourceShop = fixture.location(MPCS, "SHOP");
        ScopeContext scope = own(MPCS);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        sourceShop,
                                        batch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal("5"),
                                        new BigDecimal("1200"),
                                        null))),
                        scope));
        kernel.reset();

        UUID id = issue.handle(transfer(sourceShop, shop, "5"), scope);

        String locationCode = superuserJdbc()
                .queryForObject(
                        "select location_code from party.location where location_id = ?", String.class, sourceShop);
        Map<String, Object> numbered = superuserJdbc()
                .queryForMap(
                        """
                        select d.doc_number_display, s.series_scope, s.location_id, s.holder_device_id
                          from kernel.document d
                          join kernel.numbering_series s on s.series_id = d.series_id
                         where d.document_id = ?
                        """,
                        id);
        assertThat(numbered.get("doc_number_display")).isEqualTo("M5XF-" + locationCode + "-XFR-0000001");
        assertThat(numbered.get("series_scope")).isEqualTo("LOCATION");
        assertThat(numbered.get("location_id")).isEqualTo(sourceShop);
        assertThat(numbered.get("holder_device_id")).isNull();
    }

    @Test
    void multipleLinesKeepTheSameIdsFactsAndOutboundCostsAcrossAllThreeRecords() {
        UUID secondSku = fixture.sku(MPCS, "SUGAR1");
        UUID secondBatch = fixture.batch(secondSku, MPCS, "S1", LocalDate.of(2027, 6, 30));
        ScopeContext scope = own(MPCS);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        warehouse,
                                        secondBatch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal("10"),
                                        new BigDecimal("800"),
                                        null))),
                        scope));
        kernel.reset();

        UUID id = issue.handle(
                new IssueTransfer(
                        warehouse,
                        shop,
                        List.of(
                                new IssueTransfer.Line(batch, new BigDecimal("20")),
                                new IssueTransfer.Line(secondBatch, new BigDecimal("7")))),
                scope);

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_line where transfer_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(2);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.document_line where document_id = ?", Integer.class, id))
                .isEqualTo(2);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_OUT'",
                                Integer.class,
                                id))
                .isEqualTo(2);

        List<Map<String, Object>> matched = superuserJdbc()
                .queryForList(
                        """
                        select tl.line_id, tl.line_no, tl.sku_id, tl.batch_id, tl.qty, tl.unit_cost,
                               dl.document_line_id, dl.line_no as document_line_no, dl.sku_id as document_sku_id,
                               dl.batch_id as document_batch_id, dl.qty as document_qty,
                               dl.unit_cost_at_issue, sm.document_line_id as movement_line_id,
                               sm.sku_id as movement_sku_id, sm.batch_id as movement_batch_id,
                               sm.qty_delta, sm.unit_cost_at_movement
                          from inventory.transfer_line tl
                          join kernel.document_line dl on dl.document_line_id = tl.line_id
                          join inventory.stock_movement sm
                            on sm.document_id = tl.transfer_id
                           and sm.document_line_id = tl.line_id
                           and sm.movement_type = 'TRANSFER_OUT'
                         where tl.transfer_id = ?
                         order by tl.line_no
                        """,
                        id);
        assertThat(matched).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("document_line_id")).isEqualTo(row.get("line_id"));
            assertThat(row.get("movement_line_id")).isEqualTo(row.get("line_id"));
            assertThat(row.get("document_line_no")).isEqualTo(row.get("line_no"));
            assertThat(row.get("document_sku_id")).isEqualTo(row.get("sku_id"));
            assertThat(row.get("movement_sku_id")).isEqualTo(row.get("sku_id"));
            assertThat(row.get("document_batch_id")).isEqualTo(row.get("batch_id"));
            assertThat(row.get("movement_batch_id")).isEqualTo(row.get("batch_id"));
            assertThat((BigDecimal) row.get("document_qty")).isEqualByComparingTo((BigDecimal) row.get("qty"));
            assertThat(((BigDecimal) row.get("qty_delta")).negate()).isEqualByComparingTo((BigDecimal) row.get("qty"));
            assertThat((BigDecimal) row.get("unit_cost_at_issue"))
                    .isEqualByComparingTo((BigDecimal) row.get("unit_cost"));
            assertThat((BigDecimal) row.get("unit_cost_at_movement"))
                    .isEqualByComparingTo((BigDecimal) row.get("unit_cost"));
        });
    }

    @Test
    void destinationScopeSeesTheTransferButNotTheSourceDocumentNumber() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));

        assertThat(queries.transfer(id, own(MPCS)).orElseThrow().documentNo()).isNotBlank();
        assertThat(queries.transfer(id, at(MPCS, shop)).orElseThrow().documentNo())
                .isNull();
    }

    @Test
    void aFailedXfrIssuanceRollsBackTheTransferLinesAndOutboundMovement() {
        UUID sourceShop = fixture.location(MPCS, "SHOP");
        ScopeContext scope = own(MPCS);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        sourceShop,
                                        batch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal("5"),
                                        new BigDecimal("1200"),
                                        null))),
                        scope));
        String locationCode = superuserJdbc()
                .queryForObject(
                        "select location_code from party.location where location_id = ?", String.class, sourceShop);
        outer.run(
                scope,
                () -> numbering.registerSeries(
                        SeriesRegistration.forLocation(
                                TransferDocumentType.XFR, MPCS, sourceShop, "M5XF", locationCode, Ids.next()),
                        scope));
        kernel.reset();

        assertProblem(() -> issue.handle(transfer(sourceShop, shop, "5"), scope), "document.series_device_held");

        assertThat(qty(sourceShop)).isEqualByComparingTo("5");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer where location_id = ?",
                                Integer.class,
                                sourceShop))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where location_id = ? and movement_type = 'TRANSFER_OUT'",
                                Integer.class,
                                sourceShop))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.document"
                                        + " where doc_type_code = 'XFR' and location_id = ?",
                                Integer.class,
                                sourceShop))
                .isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aFailureAfterTakingTheXfrNumberRollsBackTheCounterAndEveryTransferWrite() {
        ScopeContext scope = own(MPCS);
        UUID seriesId = outer.run(
                scope,
                () -> numbering.registerSeries(
                        SeriesRegistration.forEntity(TransferDocumentType.XFR, MPCS, "M5XF"), scope));
        long nextNumberBefore = superuserJdbc()
                .queryForObject(
                        "select next_number from kernel.numbering_series where series_id = ?", Long.class, seriesId);
        int documentsBefore = superuserJdbc()
                .queryForObject("select count(*) from kernel.document where series_id = ?", Integer.class, seriesId);
        kernel.reset();
        doThrow(new IllegalStateException("XFR post-issue failure"))
                .when(transferDocumentType)
                .afterIssue(any(), anyList(), any());

        assertThatThrownBy(() -> issue.handle(transfer(warehouse, shop, "5"), scope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XFR post-issue failure");

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select next_number from kernel.numbering_series where series_id = ?",
                                Long.class,
                                seriesId))
                .isEqualTo(nextNumberBefore);
        assertThat(superuserJdbc().queryForObject("select count(*) from inventory.transfer", Integer.class))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement where movement_type = 'TRANSFER_OUT'",
                                Integer.class))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.document where series_id = ?", Integer.class, seriesId))
                .isEqualTo(documentsBefore);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void issueGuards() {
        UUID elsewhere = fixture.location(OTHER, "SHOP");
        assertProblem(() -> issue.handle(transfer(warehouse, shop, "1"), viewOnly()), "m5.scope.own_required");
        assertProblem(() -> issue.handle(transfer(elsewhere, shop, "1"), own(MPCS)), "m5.location.not_in_scope");
        assertProblem(() -> issue.handle(transfer(warehouse, warehouse, "1"), own(MPCS)), "m5.transfer.same_location");
        assertProblem(
                () -> issue.handle(transfer(warehouse, elsewhere, "1"), own(MPCS)), "m5.transfer.destination_invalid");
        // A session held to the warehouse cannot see the shop: issuing is entity-wide work.
        assertProblem(
                () -> issue.handle(transfer(warehouse, shop, "1"), at(MPCS, warehouse)),
                "m5.transfer.destination_invalid");
        assertProblem(
                () -> issue.handle(new IssueTransfer(warehouse, shop, List.of()), own(MPCS)),
                "m5.transfer.lines_required");
        assertProblem(() -> issue.handle(transfer(warehouse, shop, "0"), own(MPCS)), "m5.transfer.line_invalid");
        assertProblem(() -> issue.handle(transfer(warehouse, shop, "1.0001"), own(MPCS)), "m5.transfer.line_invalid");
        assertProblem(
                () -> issue.handle(
                        new IssueTransfer(warehouse, shop, List.of(new IssueTransfer.Line(Ids.next(), BigDecimal.ONE))),
                        own(MPCS)),
                "m5.batch.not_found");
        assertProblem(() -> issue.handle(transfer(warehouse, shop, "51"), own(MPCS)), "m5.transfer.insufficient_stock");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(qty(warehouse)).isEqualByComparingTo("50");
        assertThat(queries.transfers(warehouse, own(MPCS))).isEmpty();
    }

    @Test
    void receiveGuards() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        kernel.reset();

        assertProblem(() -> receive.handle(new ReceiveTransfer(id), viewOnly()), "m5.scope.own_required");
        assertProblem(() -> receive.handle(new ReceiveTransfer(Ids.next()), at(MPCS, shop)), "m5.transfer.not_found");
        // Another shop of the society does not see it at all; the source sees it but does not receive it.
        assertProblem(() -> receive.handle(new ReceiveTransfer(id), at(MPCS, otherShop)), "m5.transfer.not_found");
        assertProblem(
                () -> receive.handle(new ReceiveTransfer(id), at(MPCS, warehouse)), "m5.transfer.not_destination");
        assertProblem(() -> receive.handle(new ReceiveTransfer(id), at(MPCS, shop)), "m5.transfer.not_dispatched");
        assertThat(kernel.committedAudit()).isEmpty();

        dispatch.handle(new DispatchTransfer(id), own(MPCS));
        kernel.reset();
        receive.handle(new ReceiveTransfer(id), at(MPCS, shop));
        kernel.reset();
        assertProblem(() -> receive.handle(new ReceiveTransfer(id), at(MPCS, shop)), "m5.transfer.already_received");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(qty(shop)).isEqualByComparingTo("5");
    }

    @Test
    void dispatchGuardsAndDuplicateProtectionCommitNothing() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        kernel.reset();

        assertProblem(() -> dispatch.handle(new DispatchTransfer(id), viewOnly()), "m5.scope.own_required");
        assertProblem(() -> dispatch.handle(new DispatchTransfer(Ids.next()), own(MPCS)), "m5.transfer.not_found");
        assertProblem(() -> dispatch.handle(new DispatchTransfer(id), at(MPCS, shop)), "m5.transfer.not_source");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        dispatch.handle(new DispatchTransfer(id), own(MPCS));
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_dispatch where transfer_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement where document_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);

        kernel.reset();
        assertProblem(() -> dispatch.handle(new DispatchTransfer(id), own(MPCS)), "m5.transfer.already_dispatched");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_dispatch where transfer_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void concurrentDispatchAndReceiptAttemptsCommitEachLifecycleFactOnlyOnce() throws Exception {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));

        assertThat(race(
                        () -> dispatch.handle(new DispatchTransfer(id), own(MPCS)),
                        () -> dispatch.handle(new DispatchTransfer(id), own(MPCS))))
                .containsExactlyInAnyOrder("OK", "m5.transfer.already_dispatched");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_dispatch where transfer_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);

        UUID lineId = superuserJdbc()
                .queryForObject("select line_id from inventory.transfer_line where transfer_id = ?", UUID.class, id);
        ReceiveTransfer shortReceipt =
                new ReceiveTransfer(id, List.of(new ReceiveTransfer.Line(lineId, new BigDecimal("4.000"))));
        assertThat(race(
                        () -> receive.handle(shortReceipt, at(MPCS, shop)),
                        () -> receive.handle(shortReceipt, at(MPCS, shop))))
                .containsExactlyInAnyOrder("OK", "m5.transfer.already_received");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_receipt where transfer_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_shortfall where transfer_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_shortfall_line where transfer_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_receipt_line where transfer_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_IN'",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void issuedCancellationRestoresSourceAtFrozenCostAndIsTerminal() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        UUID lineId = superuserJdbc()
                .queryForObject("select line_id from inventory.transfer_line where transfer_id = ?", UUID.class, id);
        kernel.reset();

        cancel.handle(new CancelTransfer(id, "Order withdrawn", false), at(MPCS, warehouse));

        assertThat(qty(warehouse)).isEqualByComparingTo("50");
        assertThat(qty(shop)).isEqualByComparingTo("0");
        assertThat(queries.transfer(id, own(MPCS)).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(queries.transfer(id, at(MPCS, shop)).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(queries.transfer(id, at(MPCS, otherShop))).isEmpty();
        assertThat(queries.transfer(id, own(OTHER))).isEmpty();
        assertThat(superuserJdbc()
                        .queryForMap(
                                "select cancelled_from_status, physical_return_verified_by,"
                                        + " physical_return_verified_at, reason"
                                        + " from inventory.transfer_cancellation where transfer_id = ?",
                                id))
                .containsEntry("cancelled_from_status", "ISSUED")
                .containsEntry("physical_return_verified_by", null)
                .containsEntry("physical_return_verified_at", null)
                .containsEntry("reason", "Order withdrawn");
        assertThat(superuserJdbc()
                        .queryForList(
                                "select movement_type, location_id, document_line_id, qty_delta,"
                                        + " unit_cost_at_movement from inventory.stock_movement"
                                        + " where document_id = ? order by movement_seq",
                                id))
                .hasSize(2)
                .anySatisfy(row -> {
                    assertThat(row.get("movement_type")).isEqualTo("TRANSFER_OUT");
                    assertThat(row.get("qty_delta")).isEqualTo(new BigDecimal("-5.000"));
                })
                .anySatisfy(row -> {
                    assertThat(row.get("movement_type")).isEqualTo("TRANSFER_IN");
                    assertThat(row.get("location_id")).isEqualTo(warehouse);
                    assertThat(row.get("document_line_id")).isEqualTo(lineId);
                    assertThat(row.get("qty_delta")).isEqualTo(new BigDecimal("5.000"));
                    assertThat(row.get("unit_cost_at_movement")).isEqualTo(new BigDecimal("1200.0000"));
                });
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "TRANSFER_CANCELLED");
        assertThat(events(TransferCancelled.class)).singleElement().satisfies(event -> {
            assertThat(event.previousStatus()).isEqualTo("ISSUED");
            assertThat(event.physicalReturnVerified()).isFalse();
        });

        kernel.reset();
        assertProblem(
                () -> cancel.handle(new CancelTransfer(id, "Again", false), own(MPCS)),
                "m5.transfer.already_cancelled");
        assertProblem(() -> dispatch.handle(new DispatchTransfer(id), own(MPCS)), "m5.transfer.already_cancelled");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement where document_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(2);
    }

    @Test
    void inTransitCancellationRequiresAndRecordsVerifiedPhysicalReturn() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        dispatch.handle(new DispatchTransfer(id), own(MPCS));
        kernel.reset();

        assertProblem(
                () -> cancel.handle(new CancelTransfer(id, "Returned", false), at(MPCS, warehouse)),
                "m5.transfer.physical_return_required");
        assertThat(qty(warehouse)).isEqualByComparingTo("45");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_cancellation where transfer_id = ?",
                                Integer.class,
                                id))
                .isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        cancel.handle(new CancelTransfer(id, "Returned", true), at(MPCS, warehouse));

        assertThat(qty(warehouse)).isEqualByComparingTo("50");
        assertThat(superuserJdbc()
                        .queryForMap(
                                "select cancelled_from_status, physical_return_verified_by is not null as verified_by,"
                                        + " physical_return_verified_at is not null as verified_at"
                                        + " from inventory.transfer_cancellation where transfer_id = ?",
                                id))
                .containsEntry("cancelled_from_status", "IN_TRANSIT")
                .containsEntry("verified_by", true)
                .containsEntry("verified_at", true);
        assertThat(events(TransferCancelled.class)).singleElement().satisfies(event -> {
            assertThat(event.previousStatus()).isEqualTo("IN_TRANSIT");
            assertThat(event.physicalReturnVerified()).isTrue();
        });
    }

    @Test
    void duplicateAndReceiptCancellationRacesCommitOneTerminalDecision() throws Exception {
        UUID issued = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        assertThat(race(
                        () -> cancel.handle(new CancelTransfer(issued, "Duplicate race", false), own(MPCS)),
                        () -> cancel.handle(new CancelTransfer(issued, "Duplicate race", false), own(MPCS))))
                .containsExactlyInAnyOrder("OK", "m5.transfer.already_cancelled");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_cancellation where transfer_id = ?",
                                Integer.class,
                                issued))
                .isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_IN'",
                                Integer.class,
                                issued))
                .isEqualTo(1);

        UUID inTransit = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        dispatch.handle(new DispatchTransfer(inTransit), own(MPCS));
        List<String> outcomes = race(
                () -> receive.handle(new ReceiveTransfer(inTransit), at(MPCS, shop)),
                () -> cancel.handle(new CancelTransfer(inTransit, "Returned during race", true), own(MPCS)));
        assertThat(outcomes).contains("OK");
        assertThat(outcomes).anySatisfy(outcome -> assertThat(outcome)
                .isIn("m5.transfer.already_received", "m5.transfer.already_cancelled"));
        int receipts = superuserJdbc()
                .queryForObject(
                        "select count(*) from inventory.transfer_receipt where transfer_id = ?",
                        Integer.class,
                        inTransit);
        int cancellations = superuserJdbc()
                .queryForObject(
                        "select count(*) from inventory.transfer_cancellation where transfer_id = ?",
                        Integer.class,
                        inTransit);
        assertThat(receipts + cancellations).isEqualTo(1);
        assertThat(qty(warehouse).add(qty(shop))).isEqualByComparingTo("50");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_IN'",
                                Integer.class,
                                inTransit))
                .isEqualTo(1);
    }

    @Test
    void cancellationGuardsScopeReasonAndReceiptWithoutMutatingStock() {
        UUID issued = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        kernel.reset();
        assertProblem(
                () -> cancel.handle(new CancelTransfer(issued, "Reason", false), viewOnly()), "m5.scope.own_required");
        assertProblem(
                () -> cancel.handle(new CancelTransfer(issued, "Reason", false), at(MPCS, shop)),
                "m5.transfer.not_source");
        assertProblem(
                () -> cancel.handle(new CancelTransfer(issued, "  ", false), own(MPCS)),
                "m5.transfer.cancellation_reason_invalid");
        assertThat(qty(warehouse)).isEqualByComparingTo("45");

        dispatch.handle(new DispatchTransfer(issued), own(MPCS));
        receive.handle(new ReceiveTransfer(issued), at(MPCS, shop));
        kernel.reset();
        assertProblem(
                () -> cancel.handle(new CancelTransfer(issued, "Too late", true), own(MPCS)),
                "m5.transfer.already_received");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void cancellationRollsBackItsFactAuditAndEventWhenStockRestorationFails() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        kernel.reset();
        superuserJdbc()
                .update(
                        "update inventory.movement_sequence set last_seq = ?"
                                + " where location_id = ? and source = 'central'",
                        Long.MAX_VALUE,
                        warehouse);

        assertThatThrownBy(() -> cancel.handle(new CancelTransfer(id, "Rollback", false), own(MPCS)))
                .hasRootCauseMessage("ERROR: bigint out of range");

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_cancellation where transfer_id = ?",
                                Integer.class,
                                id))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_IN'",
                                Integer.class,
                                id))
                .isZero();
        assertThat(qty(warehouse)).isEqualByComparingTo("45");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void explicitFullReceiptPersistsEveryLineAndInvalidQuantitiesRollBackAtomically() {
        UUID secondSku = fixture.sku(MPCS, "TEA1");
        UUID secondBatch = fixture.batch(secondSku, MPCS, "T1", LocalDate.of(2027, 8, 31));
        ScopeContext source = own(MPCS);
        outer.run(
                source,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        warehouse,
                                        secondBatch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal("10"),
                                        new BigDecimal("725.2500"),
                                        null))),
                        source));
        UUID id = issue.handle(
                new IssueTransfer(
                        warehouse,
                        shop,
                        List.of(
                                new IssueTransfer.Line(batch, new BigDecimal("5.000")),
                                new IssueTransfer.Line(secondBatch, new BigDecimal("7.000")))),
                source);
        dispatch.handle(new DispatchTransfer(id), source);

        List<Map<String, Object>> sent = superuserJdbc()
                .queryForList(
                        "select line_id, qty from inventory.transfer_line where transfer_id = ? order by line_no", id);
        UUID first = (UUID) sent.get(0).get("line_id");
        UUID second = (UUID) sent.get(1).get("line_id");
        BigDecimal firstQty = (BigDecimal) sent.get(0).get("qty");
        BigDecimal secondQty = (BigDecimal) sent.get(1).get("qty");

        assertReceiptRejected(id, List.of(), "m5.transfer.receipt_lines_required");
        assertReceiptRejected(
                id,
                List.of(new ReceiveTransfer.Line(null, firstQty), new ReceiveTransfer.Line(second, secondQty)),
                "m5.transfer.receipt_line_invalid");
        assertReceiptRejected(
                id,
                List.of(
                        new ReceiveTransfer.Line(first, firstQty),
                        new ReceiveTransfer.Line(first, firstQty),
                        new ReceiveTransfer.Line(second, secondQty)),
                "m5.transfer.receipt_line_duplicate");
        assertReceiptRejected(
                id,
                List.of(new ReceiveTransfer.Line(Ids.next(), firstQty), new ReceiveTransfer.Line(second, secondQty)),
                "m5.transfer.receipt_line_unknown");
        assertReceiptRejected(
                id, List.of(new ReceiveTransfer.Line(first, firstQty)), "m5.transfer.receipt_line_missing");
        assertReceiptRejected(
                id,
                List.of(
                        new ReceiveTransfer.Line(first, new BigDecimal("-1")),
                        new ReceiveTransfer.Line(second, secondQty)),
                "m5.transfer.receipt_qty_invalid");
        assertReceiptRejected(
                id,
                List.of(
                        new ReceiveTransfer.Line(first, firstQty.setScale(4)),
                        new ReceiveTransfer.Line(second, secondQty)),
                "m5.transfer.receipt_qty_invalid");
        assertReceiptRejected(
                id,
                List.of(
                        new ReceiveTransfer.Line(first, firstQty.add(BigDecimal.ONE)),
                        new ReceiveTransfer.Line(second, secondQty)),
                "m5.transfer.receipt_qty_exceeds_sent");
        assertReceiptRejected(
                id,
                List.of(new ReceiveTransfer.Line(first, BigDecimal.ZERO), new ReceiveTransfer.Line(second, secondQty)),
                "m5.transfer.receipt_qty_invalid");

        kernel.reset();
        receive.handle(
                new ReceiveTransfer(
                        id,
                        List.of(
                                new ReceiveTransfer.Line(first, firstQty),
                                new ReceiveTransfer.Line(second, secondQty))),
                at(MPCS, shop));

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_receipt_line where transfer_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(2);
        assertThat(superuserJdbc()
                        .queryForObject(
                                """
                                select count(*)
                                  from inventory.transfer_receipt_line rl
                                  join inventory.transfer_line tl
                                    on tl.transfer_id = rl.transfer_id and tl.line_id = rl.transfer_line_id
                                  join inventory.stock_movement sm
                                    on sm.document_id = rl.transfer_id
                                   and sm.document_line_id = rl.transfer_line_id
                                   and sm.movement_type = 'TRANSFER_IN'
                                 where rl.transfer_id = ?
                                   and rl.received_qty = sm.qty_delta
                                   and tl.unit_cost = sm.unit_cost_at_movement
                                """,
                                Integer.class,
                                id))
                .isEqualTo(2);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "TRANSFER_RECEIVED");
        assertThat(events(TransferReceived.class)).hasSize(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_shortfall where transfer_id = ?",
                                Integer.class,
                                id))
                .isZero();
    }

    @Test
    void shortReceiptPostsOnlyActualsAndCreatesOneUnpostedDestinationDraft() {
        UUID secondSku = fixture.sku(MPCS, "TEA2");
        UUID secondBatch = fixture.batch(secondSku, MPCS, "T2", LocalDate.of(2027, 9, 30));
        UUID thirdSku = fixture.sku(MPCS, "TEA3");
        UUID thirdBatch = fixture.batch(thirdSku, MPCS, "T3", LocalDate.of(2027, 10, 31));
        ScopeContext source = own(MPCS);
        outer.run(
                source,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(
                                        new Movement(
                                                warehouse,
                                                batch,
                                                LotCondition.GOOD,
                                                MovementType.RECEIPT,
                                                new BigDecimal("100"),
                                                new BigDecimal("1200.0000"),
                                                null),
                                        new Movement(
                                                warehouse,
                                                secondBatch,
                                                LotCondition.GOOD,
                                                MovementType.RECEIPT,
                                                new BigDecimal("10"),
                                                new BigDecimal("725.2500"),
                                                null),
                                        new Movement(
                                                warehouse,
                                                thirdBatch,
                                                LotCondition.GOOD,
                                                MovementType.RECEIPT,
                                                new BigDecimal("10"),
                                                new BigDecimal("500.1250"),
                                                null))),
                        source));
        UUID id = issue.handle(
                new IssueTransfer(
                        warehouse,
                        shop,
                        List.of(
                                new IssueTransfer.Line(batch, new BigDecimal("100.000")),
                                new IssueTransfer.Line(secondBatch, new BigDecimal("7.000")),
                                new IssueTransfer.Line(thirdBatch, new BigDecimal("6.000")))),
                source);
        dispatch.handle(new DispatchTransfer(id), source);
        List<Map<String, Object>> sent = superuserJdbc()
                .queryForList(
                        "select line_id, qty, unit_cost from inventory.transfer_line"
                                + " where transfer_id = ? order by line_no",
                        id);
        UUID first = (UUID) sent.get(0).get("line_id");
        UUID second = (UUID) sent.get(1).get("line_id");
        UUID third = (UUID) sent.get(2).get("line_id");

        kernel.reset();
        receive.handle(
                new ReceiveTransfer(
                        id,
                        List.of(
                                new ReceiveTransfer.Line(first, new BigDecimal("95.000")),
                                new ReceiveTransfer.Line(second, new BigDecimal("7.000")),
                                new ReceiveTransfer.Line(third, new BigDecimal("4.500")))),
                at(MPCS, shop));

        assertThat(superuserJdbc()
                        .queryForList(
                                "select transfer_line_id, received_qty from inventory.transfer_receipt_line"
                                        + " where transfer_id = ? order by transfer_line_id",
                                id))
                .extracting(row -> row.get("received_qty"))
                .containsExactlyInAnyOrder(new BigDecimal("95.000"), new BigDecimal("7.000"), new BigDecimal("4.500"));
        assertThat(superuserJdbc()
                        .queryForList(
                                "select document_line_id, qty_delta, unit_cost_at_movement"
                                        + " from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_IN'",
                                id))
                .allSatisfy(row -> {
                    UUID lineId = (UUID) row.get("document_line_id");
                    Map<String, Object> issued = sent.stream()
                            .filter(candidate -> candidate.get("line_id").equals(lineId))
                            .findFirst()
                            .orElseThrow();
                    BigDecimal expectedQty = lineId.equals(first)
                            ? new BigDecimal("95.000")
                            : lineId.equals(second) ? new BigDecimal("7.000") : new BigDecimal("4.500");
                    assertThat((BigDecimal) row.get("qty_delta")).isEqualByComparingTo(expectedQty);
                    assertThat((BigDecimal) row.get("unit_cost_at_movement"))
                            .isEqualByComparingTo((BigDecimal) issued.get("unit_cost"));
                })
                .hasSize(3);
        Map<String, Object> draft = superuserJdbc()
                .queryForMap(
                        "select shortfall_id, owner_entity_id, location_id, from_location_id, category, status"
                                + " from inventory.transfer_shortfall where transfer_id = ?",
                        id);
        assertThat(draft)
                .containsEntry("owner_entity_id", MPCS)
                .containsEntry("location_id", shop)
                .containsEntry("from_location_id", warehouse)
                .containsEntry("category", "DAMAGED_IN_TRANSIT")
                .containsEntry("status", "DRAFT");
        assertThat(superuserJdbc()
                        .queryForList(
                                "select transfer_line_id, shortfall_qty, unit_cost"
                                        + " from inventory.transfer_shortfall_line where transfer_id = ?",
                                id))
                .allSatisfy(row -> {
                    UUID lineId = (UUID) row.get("transfer_line_id");
                    BigDecimal expected = lineId.equals(first) ? new BigDecimal("5.000") : new BigDecimal("1.500");
                    assertThat((BigDecimal) row.get("shortfall_qty")).isEqualByComparingTo(expected);
                    BigDecimal issuedCost = (BigDecimal) sent.stream()
                            .filter(candidate -> candidate.get("line_id").equals(lineId))
                            .findFirst()
                            .orElseThrow()
                            .get("unit_cost");
                    assertThat((BigDecimal) row.get("unit_cost")).isEqualByComparingTo(issuedCost);
                })
                .hasSize(2);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement where document_id = ?"
                                        + " and movement_type in ('COUNT_ADJUST', 'WRITE_OFF')",
                                Integer.class,
                                id))
                .isZero();
        TransferView view = queries.transfer(id, at(MPCS, shop)).orElseThrow();
        assertThat(view.shortfallId()).isEqualTo(draft.get("shortfall_id"));
        assertThat(view.shortfallStatus()).isEqualTo("DRAFT");
        assertThat(view.shortfallCategory()).isEqualTo("DAMAGED_IN_TRANSIT");
        assertThat(view.lines())
                .extracting(TransferView.Line::shortfallQty)
                .containsExactly(new BigDecimal("5.000"), null, new BigDecimal("1.500"));
        assertThat(queries.transfer(id, at(MPCS, warehouse)))
                .get()
                .extracting(TransferView::shortfallId)
                .isEqualTo(draft.get("shortfall_id"));
        assertThat(queries.transfer(id, at(MPCS, otherShop))).isEmpty();
        assertThat(queries.transfer(id, own(OTHER))).isEmpty();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "TRANSFER_RECEIVED", "TRANSFER_SHORTFALL_DRAFTED");
        assertThat(events(TransferReceived.class)).hasSize(1);
        assertThat(events(TransferShortfallDrafted.class)).singleElement().satisfies(event -> {
            assertThat(event.shortfallId()).isEqualTo(draft.get("shortfall_id"));
            assertThat(event.shortfallQty()).isEqualByComparingTo("6.500");
            assertThat(event.shortfallValue()).isEqualByComparingTo("6750.1875");
        });
    }

    @Test
    void shortReceiptRollsBackReceiptDraftAndAuditWhenMovementPostingFails() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        dispatch.handle(new DispatchTransfer(id), own(MPCS));
        UUID lineId = superuserJdbc()
                .queryForObject("select line_id from inventory.transfer_line where transfer_id = ?", UUID.class, id);
        kernel.reset();
        superuserJdbc()
                .update(
                        "insert into inventory.movement_sequence"
                                + " (location_id, source, owner_entity_id, last_seq) values (?, 'central', ?, ?)",
                        shop,
                        MPCS,
                        Long.MAX_VALUE);

        assertThatThrownBy(() -> receive.handle(
                        new ReceiveTransfer(id, List.of(new ReceiveTransfer.Line(lineId, new BigDecimal("4.000")))),
                        at(MPCS, shop)))
                .hasRootCauseMessage("ERROR: bigint out of range");

        for (String table :
                List.of("transfer_receipt", "transfer_receipt_line", "transfer_shortfall", "transfer_shortfall_line")) {
            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select count(*) from inventory." + table + " where transfer_id = ?",
                                    Integer.class,
                                    id))
                    .as(table)
                    .isZero();
        }
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_IN'",
                                Integer.class,
                                id))
                .isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void receiptLineAndCancellationCompositeIdentityIsEnforcedByTheDatabase() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        UUID lineId = superuserJdbc()
                .queryForObject("select line_id from inventory.transfer_line where transfer_id = ?", UUID.class, id);
        superuserJdbc()
                .update(
                        "insert into inventory.transfer_receipt"
                                + " (transfer_id, owner_entity_id, location_id, from_location_id, received_by)"
                                + " values (?, ?, ?, ?, ?)",
                        id,
                        MPCS,
                        shop,
                        warehouse,
                        InventoryFixture.USER);

        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "insert into inventory.transfer_receipt_line"
                                        + " (transfer_id, transfer_line_id, owner_entity_id, location_id,"
                                        + " from_location_id, received_qty) values (?, ?, ?, ?, ?, 5)",
                                id,
                                lineId,
                                MPCS,
                                shop,
                                otherShop))
                .hasMessageContaining("violates foreign key constraint");
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "insert into inventory.transfer_receipt_line"
                                        + " (transfer_id, transfer_line_id, owner_entity_id, location_id,"
                                        + " from_location_id, received_qty) values (?, ?, ?, ?, ?, -1)",
                                id,
                                lineId,
                                MPCS,
                                shop,
                                warehouse))
                .hasMessageContaining("violates check constraint");
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "insert into inventory.transfer_cancellation"
                                        + " (transfer_id, owner_entity_id, location_id, to_location_id,"
                                        + " cancelled_by, reason) values (?, ?, ?, ?, ?, 'test')",
                                id,
                                MPCS,
                                warehouse,
                                otherShop,
                                InventoryFixture.USER))
                .hasMessageContaining("violates foreign key constraint");
    }

    // ---- helpers --------------------------------------------------------------------------

    private UUID heldSku() {
        return queries.balances(warehouse, null, true, own(MPCS)).get(0).skuId();
    }

    private IssueTransfer transfer(UUID from, UUID to, String qty) {
        return new IssueTransfer(from, to, List.of(new IssueTransfer.Line(batch, new BigDecimal(qty))));
    }

    private BigDecimal qty(UUID location) {
        return queries.balances(location, null, true, own(MPCS)).stream()
                .map(LotBalance::qtyOnHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ScopeContext viewOnly() {
        lk.coopfed.knoweb.kernel.api.Scope scope = new lk.coopfed.knoweb.kernel.api.Scope(MPCS, null);
        return new ScopeContext(
                InventoryFixture.USER,
                null,
                MPCS,
                List.of(scope),
                scope,
                lk.coopfed.knoweb.kernel.api.PolicyClass.FEDERATION_VIEW,
                java.util.Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String id) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(id));
    }

    private void assertReceiptRejected(UUID transferId, List<ReceiveTransfer.Line> lines, String problemId) {
        kernel.reset();
        assertProblem(() -> receive.handle(new ReceiveTransfer(transferId, lines), at(MPCS, shop)), problemId);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_receipt where transfer_id = ?",
                                Integer.class,
                                transferId))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_receipt_line where transfer_id = ?",
                                Integer.class,
                                transferId))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where document_id = ? and movement_type = 'TRANSFER_IN'",
                                Integer.class,
                                transferId))
                .isZero();
    }

    private static List<String> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> firstResult = pool.submit(() -> outcome(start, first));
            Future<String> secondResult = pool.submit(() -> outcome(start, second));
            start.countDown();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static String outcome(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try {
            action.run();
            return "OK";
        } catch (ProblemException problem) {
            return problem.messageId();
        }
    }
}
