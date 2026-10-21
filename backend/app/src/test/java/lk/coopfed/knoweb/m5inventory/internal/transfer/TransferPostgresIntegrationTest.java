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
import lk.coopfed.knoweb.m5inventory.api.DispatchTransfer;
import lk.coopfed.knoweb.m5inventory.api.IssueTransfer;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.ReceiveTransfer;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.TransferDispatched;
import lk.coopfed.knoweb.m5inventory.api.TransferIssued;
import lk.coopfed.knoweb.m5inventory.api.TransferReceived;
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
}
