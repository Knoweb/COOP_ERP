package lk.coopfed.knoweb.m6pos;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.event.EventConsumerDispatcher;
import lk.coopfed.knoweb.kernel.internal.event.EventConsumerDispatcher.DeliveryResult;
import lk.coopfed.knoweb.kernel.internal.event.OutboxMessage;
import lk.coopfed.knoweb.kernel.internal.sync.SyncTestKeys;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockSold;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m6pos.api.ReceiptRecorded;
import lk.coopfed.knoweb.m6pos.api.TillSessionRecorded;
import lk.coopfed.knoweb.m6pos.query.PosQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import lk.coopfed.knoweb.testsupport.TillSimulator;
import lk.coopfed.knoweb.testsupport.TillSimulator.Sale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Demo phase 3, the shop, end to end: a till at a society's shop opens a session, sells three
 * items by barcode, closes, and uploads through the sync contract (doc 32, K-08); the gateway
 * puts each fact in the outbox with the device as its source; the consumers apply them as the
 * relay would deliver them: M6 records the session and the receipt as issued from the till's
 * series (26A section 10), M5's ReceiptConsumer posts SALE at the shop (25A section 6.2), and
 * the shop's stock goes down. An oversell is applied and flagged, never refused (AGENTS.md).
 */
class TillSaleEndToEndIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e6a0-0000-7000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("0190e6a0-0000-7000-8000-000000000101");
    private static final UUID POSITION = UUID.fromString("0190e6a0-0000-7000-8000-000000000201");
    private static final UUID DEVICE = UUID.fromString("0190e6a0-0000-7000-8000-000000000301");
    private static final UUID SERIES = UUID.fromString("0190e6a0-0000-7000-8000-000000000401");
    private static final UUID REPLACEMENT = UUID.fromString("0190e6a0-0000-7000-8000-000000000302");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationContext context;

    @Autowired
    lk.coopfed.knoweb.kernel.internal.event.EventConsumerRegistry registry;

    @Autowired
    lk.coopfed.knoweb.kernel.internal.event.InboxGuard inbox;

    @Autowired
    lk.coopfed.knoweb.kernel.api.AuditFacade audit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * The kernel's own dispatcher, as the broker runtime builds it (the test profile runs no
     * broker; RevokeStopsThePermissionPostgresIntegrationTest does the same).
     */
    private EventConsumerDispatcher dispatcher() {
        return new EventConsumerDispatcher(
                registry,
                inbox,
                new lk.coopfed.knoweb.kernel.internal.event.DeadLetter(message -> {}),
                audit,
                json,
                jdbc,
                transactionManager);
    }

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    InventoryQueries inventory;

    @Autowired
    PosQueries pos;

    private InventoryFixture fixture;
    private UUID rice;
    private UUID dhal;
    private UUID soap;

    @BeforeEach
    void aShopWithStockAndAnEnrolledTill() {
        JdbcTemplate db = superuserJdbc();
        forget(db);
        fixture = new InventoryFixture(db);
        fixture.clean();
        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, status)
                values (?, ?, 'M6S1', 'SHOP', 'Demo test shop', 'ACTIVE')
                """,
                SHOP,
                MPCS);
        db.update(
                "insert into party.till_position (till_position_id, location_id, position_no, owner_entity_id) values (?, ?, 1, ?)",
                POSITION,
                SHOP,
                MPCS);
        db.update(
                """
                insert into party.device (device_id, hardware_serial, device_kind, owner_entity_id,
                                          current_till_position_id, status, enrolled_at, location_id)
                values (?, 'SN-M6-0001', 'POS_TERMINAL', ?, ?, 'ACTIVE', now(), ?)
                """,
                DEVICE,
                MPCS,
                POSITION,
                SHOP);
        db.update("insert into kernel.device_sync_cursor (device_id, owner_entity_id) values (?, ?)", DEVICE, MPCS);

        rice = item(db, "RICE5", "4790001000011", "10");
        dhal = item(db, "DHAL1", "4790001000028", "10");
        soap = item(db, "SOAP1", "4790001000035", "10");
        kernel.reset();
    }

    @AfterEach
    void forgetAfterwards() {
        forget(superuserJdbc());
        fixture.clean();
    }

    @Test
    void theTillSellsThreeItemsByBarcodeAndAfterSyncTheShopsStockGoesDown() throws Exception {
        TillSimulator till = till();
        till.refreshSnapshot();
        UUID session = till.openSession(new BigDecimal("1000.00"));
        UUID receipt = till.sell(List.of(
                new Sale("4790001000011", new BigDecimal("2"), new BigDecimal("1450.00")),
                new Sale("4790001000028", new BigDecimal("1"), new BigDecimal("380.00")),
                new Sale("4790001000035", new BigDecimal("3"), new BigDecimal("95.00"))));
        till.closeSession(new BigDecimal("4565.00"));

        till.drain(50, Instant.now().plusSeconds(30));
        assertThat(till.pending()).isZero();
        deliverTheTillsEvents();

        // The shop's stock went down, in the device's own movement sequence.
        assertThat(onHand(rice)).isEqualByComparingTo("8");
        assertThat(onHand(dhal)).isEqualByComparingTo("9");
        assertThat(onHand(soap)).isEqualByComparingTo("7");
        assertThat(inventory.movementsOf(receipt, own(MPCS))).hasSize(3).allSatisfy(m -> assertThat(m.movementType())
                .isEqualTo("SALE"));
        assertThat(superuserJdbc()
                        .queryForList(
                                "select distinct source from inventory.stock_movement where document_id = ?",
                                String.class,
                                receipt))
                .containsExactly(DEVICE.toString());

        // The receipt shows centrally, as issued from the till's series, and the session closed.
        ScopeContext office = own(MPCS);
        assertThat(pos.receipts(SHOP, office)).singleElement().satisfies(r -> {
            assertThat(r.documentId()).isEqualTo(receipt);
            assertThat(r.docNumberDisplay()).isEqualTo("M6S1-1-1");
            assertThat(r.grossAmount()).isEqualByComparingTo("3565.00");
            assertThat(r.sessionId()).isEqualTo(session);
            assertThat(r.flags()).isEmpty();
            assertThat(r.lines()).hasSize(3);
            assertThat(r.tenders()).singleElement().satisfies(t -> {
                assertThat(t.kind()).isEqualTo("CASH");
                assertThat(t.amount()).isEqualByComparingTo("3565.00");
            });
            assertThat(r.netAmount()).isEqualByComparingTo("3565.00");
        });
        assertThat(superuserJdbc()
                        .queryForMap(
                                "select series_id, doc_number, origin from pos.receipt where document_id = ?", receipt))
                .containsEntry("series_id", SERIES)
                .containsEntry("doc_number", 1L)
                .containsEntry("origin", "OFFLINE");
        assertThat(pos.sessions(SHOP, office)).singleElement().satisfies(s -> {
            assertThat(s.closedAt()).isNotNull();
            assertThat(s.variance()).isEqualByComparingTo("0");
        });

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains(
                        "TILL_SESSION_OPENED", "RECEIPT_RECORDED", "STOCK_POSTED", "STOCK_SOLD", "TILL_SESSION_CLOSED")
                .doesNotContain("RECEIPT_FLAGGED", "STOCK_LOT_NEGATIVE");
        assertThat(kernel.committedEvents())
                .filteredOn(ReceiptRecorded.class::isInstance)
                .hasSize(1);
        assertThat(kernel.committedEvents())
                .filteredOn(StockSold.class::isInstance)
                .hasSize(1);
        assertThat(kernel.committedEvents())
                .filteredOn(TillSessionRecorded.class::isInstance)
                .hasSize(2);

        // Over HTTP, the office reads the receipt; the shop's own session too.
        JsonNode receipts = http.exchange(
                        "/v1/pos/receipts?locationId=" + SHOP,
                        HttpMethod.GET,
                        new HttpEntity<>(TestIdentityProvider.entityWideHeaders(InventoryFixture.USER, MPCS)),
                        JsonNode.class)
                .getBody();
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0).get("docNumberDisplay").asText()).isEqualTo("M6S1-1-1");
        assertThat(receipts.get(0).get("tenders").get(0).get("kind").asText()).isEqualTo("CASH");
        JsonNode sessions = http.exchange(
                        "/v1/pos/sessions?locationId=" + SHOP,
                        HttpMethod.GET,
                        new HttpEntity<>(TestIdentityProvider.entityWideHeaders(InventoryFixture.USER, MPCS)),
                        JsonNode.class)
                .getBody();
        assertThat(sessions.get(0).get("status").asText()).isEqualTo("CLOSED");

        // A redelivery of every event changes nothing.
        deliverTheTillsEvents();
        assertThat(onHand(rice)).isEqualByComparingTo("8");
        assertThat(pos.receipts(SHOP, office)).hasSize(1);
    }

    @Test
    void anOversellIsAppliedAndFlaggedNeverRefused() throws Exception {
        TillSimulator till = till();
        till.refreshSnapshot();
        till.openSession(BigDecimal.ZERO);
        till.sell(List.of(new Sale("4790001000011", new BigDecimal("12"), new BigDecimal("1450.00"))));
        till.drain(50, Instant.now().plusSeconds(30));
        deliverTheTillsEvents();

        assertThat(onHand(rice)).isEqualByComparingTo("-2");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("RECEIPT_RECORDED", "STOCK_SOLD", "STOCK_LOT_NEGATIVE");
    }

    @Test
    void aReplacementTillOnThePositionContinuesTheNumbersCentralHasSeen() throws Exception {
        registerTheSeries();

        TillSimulator first = till();
        first.refreshSnapshot();
        first.openSession(BigDecimal.ZERO);
        UUID one = first.sell(List.of(new Sale("4790001000011", BigDecimal.ONE, new BigDecimal("1450.00"))));
        UUID two = first.sell(List.of(new Sale("4790001000028", BigDecimal.ONE, new BigDecimal("380.00"))));
        first.closeSession(new BigDecimal("1830.00"));
        first.drain(50, Instant.now().plusSeconds(30));
        deliverTheTillsEvents(DEVICE);

        // Central saw 1 and 2: the next number it gives out for the series is 3.
        assertThat(nextNumber()).isEqualTo(3L);

        // The till is replaced: a new device on the same position takes the next number from
        // central (what enrolment and a holder change return, doc 32 section 8).
        replaceTheTill();
        TillSimulator second = till(REPLACEMENT).numberingFrom(nextNumber());
        second.refreshSnapshot();
        second.openSession(BigDecimal.ZERO);
        UUID three = second.sell(List.of(new Sale("4790001000035", BigDecimal.ONE, new BigDecimal("95.00"))));
        second.closeSession(new BigDecimal("95.00"));
        second.drain(50, Instant.now().plusSeconds(30));
        deliverTheTillsEvents(REPLACEMENT);

        assertThat(numberOf(one)).isEqualTo(1L);
        assertThat(numberOf(two)).isEqualTo(2L);
        assertThat(numberOf(three)).isEqualTo(3L);
        assertThat(nextNumber()).isEqualTo(4L);
        assertThat(pos.receipts(SHOP, own(MPCS))).hasSize(3).allSatisfy(r -> assertThat(r.flags())
                .isEmpty());
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .doesNotContain("RECEIPT_FLAGGED", "RECEIPT_NUMBER_DUPLICATED");
    }

    @Test
    void aDuplicateNumberIsStoredAndFlaggedNeverRefused() throws Exception {
        registerTheSeries();

        TillSimulator first = till();
        first.refreshSnapshot();
        first.openSession(BigDecimal.ZERO);
        UUID original = first.sell(List.of(new Sale("4790001000011", BigDecimal.ONE, new BigDecimal("1450.00"))));
        first.drain(50, Instant.now().plusSeconds(30));
        deliverTheTillsEvents(DEVICE);

        // A replacement that did not ask central where the series stands starts again at 1.
        replaceTheTill();
        TillSimulator second = till(REPLACEMENT).numberingFrom(1);
        second.refreshSnapshot();
        second.openSession(BigDecimal.ZERO);
        UUID duplicate = second.sell(List.of(new Sale("4790001000028", BigDecimal.ONE, new BigDecimal("380.00"))));
        second.drain(50, Instant.now().plusSeconds(30));
        kernel.reset();
        deliverTheTillsEvents(REPLACEMENT);

        // Both receipts are kept; the second carries the flag, and an ALERT names the first.
        assertThat(numberOf(original)).isEqualTo(1L);
        assertThat(numberOf(duplicate)).isEqualTo(1L);
        assertThat(pos.receipts(SHOP, own(MPCS)))
                .filteredOn(r -> r.documentId().equals(duplicate))
                .singleElement()
                .satisfies(r -> assertThat(r.flags()).containsExactly("DUPLICATE_NUMBER"));
        assertThat(pos.receipts(SHOP, own(MPCS)))
                .filteredOn(r -> r.documentId().equals(original))
                .singleElement()
                .satisfies(r -> assertThat(r.flags()).isEmpty());
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("RECEIPT_NUMBER_DUPLICATED"))
                .singleElement()
                .satisfies(a -> assertThat(String.valueOf(a.after())).contains(original.toString()));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("RECEIPT_RECORDED", "RECEIPT_FLAGGED");
        assertThat(kernel.committedEvents())
                .filteredOn(ReceiptRecorded.class::isInstance)
                .map(ReceiptRecorded.class::cast)
                .singleElement()
                .satisfies(e -> assertThat(e.flags()).containsExactly("DUPLICATE_NUMBER"));
        // A duplicate never moves the high-water mark back.
        assertThat(nextNumber()).isEqualTo(2L);
    }

    // ---- helpers --------------------------------------------------------------------------

    private TillSimulator till() {
        return till(DEVICE);
    }

    private TillSimulator till(UUID device) {
        return new TillSimulator(http, json, device, SHOP, SyncTestKeys.signingKey(context))
                .sellsAs(MPCS, POSITION, SERIES, "M6S1-1");
    }

    /** The position's RCT series, held by the first till, as M1 registers it at enrolment. */
    private void registerTheSeries() {
        superuserJdbc()
                .update(
                        """
                        insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id,
                                                             location_id, till_position_id, prefix, holder_device_id)
                        values (?, 'RCT', 'TILL_POSITION', ?, ?, ?, 'M6S1-1', ?)
                        """,
                        SERIES,
                        MPCS,
                        SHOP,
                        POSITION,
                        DEVICE);
    }

    /** The first till leaves the position; a replacement device takes it and its series. */
    private void replaceTheTill() {
        JdbcTemplate db = superuserJdbc();
        db.update("update party.device set current_till_position_id = null where device_id = ?", DEVICE);
        db.update(
                """
                insert into party.device (device_id, hardware_serial, device_kind, owner_entity_id,
                                          current_till_position_id, status, enrolled_at, location_id)
                values (?, 'SN-M6-0002', 'POS_TERMINAL', ?, ?, 'ACTIVE', now(), ?)
                """,
                REPLACEMENT,
                MPCS,
                POSITION,
                SHOP);
        db.update(
                "insert into kernel.device_sync_cursor (device_id, owner_entity_id) values (?, ?)", REPLACEMENT, MPCS);
        db.update("update kernel.numbering_series set holder_device_id = ? where series_id = ?", REPLACEMENT, SERIES);
    }

    private long nextNumber() {
        return superuserJdbc()
                .queryForObject(
                        "select next_number from kernel.numbering_series where series_id = ?", Long.class, SERIES);
    }

    private long numberOf(UUID receipt) {
        return superuserJdbc()
                .queryForObject("select doc_number from pos.receipt where document_id = ?", Long.class, receipt);
    }

    /**
     * What the outbox relay does for the device's facts: each accepted event, in the device's
     * order, to every consumer of its type (M6's and M5's), as the consumer framework delivers it.
     */
    private void deliverTheTillsEvents() {
        deliverTheTillsEvents(DEVICE);
    }

    private void deliverTheTillsEvents(UUID device) {
        List<OutboxMessage> messages = superuserJdbc()
                .query(
                        """
                        select event_id, event_type, occurred_at, source, source_seq, owner_entity_id, location_id,
                               aggregate_type, aggregate_id, correlation_id, causation_id, actor_user_id,
                               engine_version, payload::text as payload
                          from kernel.event_outbox
                         where source = ?
                         order by source_seq
                        """,
                        (rs, i) -> new OutboxMessage(
                                rs.getObject("event_id", UUID.class),
                                rs.getString("event_type"),
                                rs.getTimestamp("occurred_at").toInstant(),
                                rs.getString("source"),
                                rs.getLong("source_seq"),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getString("aggregate_type"),
                                rs.getObject("aggregate_id", UUID.class),
                                rs.getObject("correlation_id", UUID.class),
                                rs.getObject("causation_id", UUID.class),
                                rs.getObject("actor_user_id", UUID.class),
                                rs.getString("engine_version"),
                                rs.getString("payload")),
                        device.toString());
        Map<String, List<String>> consumers = Map.of(
                "till_session.opened.v1", List.of("m6.sessions"),
                "till_session.closed.v1", List.of("m6.sessions"),
                "receipt.issued.v1", List.of("m6.receipts", "m5.sales"));
        assertThat(messages).isNotEmpty();
        for (OutboxMessage message : messages) {
            for (String consumer : consumers.getOrDefault(message.eventType(), List.of())) {
                assertThat(dispatcher().deliver(consumer, message, 1))
                        .as(consumer + " applies " + message.eventType())
                        .isIn(DeliveryResult.APPLIED, DeliveryResult.DUPLICATE);
            }
        }
    }

    /** An item of the society with a barcode, and a GOOD lot of it at the shop. */
    private UUID item(JdbcTemplate db, String code, String barcode, String qty) {
        UUID sku = fixture.sku(MPCS, code);
        UUID batch = fixture.batch(sku, MPCS, code, LocalDate.of(2027, 6, 30));
        db.update(
                "insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id)"
                        + " values (?, 'EAN13', ?, 'EA', ?)",
                barcode,
                sku,
                MPCS);
        outer.run(
                own(MPCS),
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        SHOP,
                                        batch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal(qty),
                                        new BigDecimal("100"),
                                        null))),
                        own(MPCS)));
        return sku;
    }

    private BigDecimal onHand(UUID sku) {
        return inventory.balances(SHOP, sku, true, own(MPCS)).stream()
                .map(LotBalance::qtyOnHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void forget(JdbcTemplate db) {
        db.execute("truncate table pos.receipt_tender, pos.receipt_line, pos.receipt, pos.till_session_close,"
                + " pos.till_session");
        db.update("delete from kernel.event_outbox where owner_entity_id = ?", MPCS);
        for (String table : List.of(
                "kernel.sync_event",
                "kernel.sync_quarantine",
                "kernel.device_heartbeat",
                "kernel.device_sync_cursor",
                "kernel.change_log",
                "kernel.location_snapshot_version")) {
            db.update("delete from " + table + " where owner_entity_id = ?", MPCS);
        }
        db.update("delete from catalogue.sku_barcode where owner_entity_id = ?", MPCS);
        db.update("delete from kernel.numbering_series where series_id = ?", SERIES);
        db.update("delete from party.device where owner_entity_id = ?", MPCS);
        db.update("delete from party.till_position where owner_entity_id = ?", MPCS);
        db.update("delete from kernel.location_business_date where location_id = ?", SHOP);
        db.update("delete from party.location where owner_entity_id = ?", MPCS);
    }
}
