package lk.coopfed.knoweb.m5inventory.internal.consumers;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.system;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PickListCreated;
import lk.coopfed.knoweb.m5inventory.api.PickListDispatched;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockMoved;
import lk.coopfed.knoweb.m5inventory.api.StockReceived;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.PickListView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The consumers of M4's events (25A sections 6.2 and 9, "Consumers"; doc 25 flows 6.1 and the
 * delivery of doc 24 section 4.2), driven by the payloads M4's event records publish (GrnConfirmed,
 * DeliveryNoteIssued, DeliveryNoteDispatched; the field names of those records), delivered as the
 * consumer framework delivers them: in the OWN scope of the event's owner, with no user. M4 is not
 * on main yet; these payloads are the contract its records carry.
 *
 * <p>The demo chain: the Federation's warehouse holds two batches; a delivery note to a distributor
 * reserves them FEFO; dispatch moves them out of the Federation's stock; the distributor's GRN puts
 * them, with a damaged unit, into the distributor's warehouse at the trade price.
 */
class ConsumersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID DISTRIBUTOR = UUID.fromString("0190e678-0000-7000-8000-000000000003");

    @Autowired
    GrnConsumer grns;

    @Autowired
    DeliveryConsumer deliveries;

    @Autowired
    InventoryQueries queries;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    ObjectMapper mapper;

    private InventoryFixture fixture;
    private UUID federationWarehouse;
    private UUID distributorWarehouse;
    private UUID sku;
    private UUID early;
    private UUID late;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        federationWarehouse = fixture.location(FEDERATION, "WAREHOUSE");
        distributorWarehouse = fixture.location(DISTRIBUTOR, "WAREHOUSE");
        sku = fixture.sku(FEDERATION, "MILK400");
        early = fixture.batch(sku, FEDERATION, "E1", LocalDate.of(2027, 3, 31));
        late = fixture.batch(sku, FEDERATION, "L1", LocalDate.of(2027, 9, 30));
        outer.run(
                own(FEDERATION),
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(
                                        receipt(federationWarehouse, early, "10", "900"),
                                        receipt(federationWarehouse, late, "10", "950"))),
                        own(FEDERATION)));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void theDemoChainReservesDispatchesAndReceivesWithOwnershipPassingAtTheGrn() {
        UUID deliveryNote = Ids.next();
        UUID line = Ids.next();

        // 1. delivery_note.issued.v1: 15 units reserved FEFO, 10 of the early batch and 5 of the late
        deliveries.onIssued(issued(deliveryNote, FEDERATION, line, sku, null, "15"), system(FEDERATION));

        PickListView pickList = queries.pickList(deliveryNote, own(FEDERATION)).orElseThrow();
        assertThat(pickList.status()).isEqualTo("OPEN");
        assertThat(pickList.lines())
                .extracting(PickListView.Pick::batchId, p -> p.qty().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(early, 10), org.assertj.core.groups.Tuple.tuple(late, 5));
        assertThat(available(federationWarehouse)).isEqualByComparingTo("5");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("PICK_LIST_CREATED");
        assertThat(events(PickListCreated.class)).singleElement().satisfies(e -> {
            assertThat(e.pickedQty()).isEqualByComparingTo("15");
            assertThat(e.shortQty()).isEqualByComparingTo("0");
        });

        // A redelivery of the same event creates nothing more.
        deliveries.onIssued(issued(deliveryNote, FEDERATION, line, sku, null, "15"), system(FEDERATION));
        assertThat(available(federationWarehouse)).isEqualByComparingTo("5");

        // 2. delivery_note.dispatched.v1: the picked units leave the Federation's lots, in transit
        kernel.reset();
        deliveries.onDispatched(dispatched(deliveryNote, FEDERATION), system(FEDERATION));

        assertThat(queries.pickList(deliveryNote, own(FEDERATION)).orElseThrow().status())
                .isEqualTo("DISPATCHED");
        assertThat(lots(federationWarehouse, FEDERATION))
                .extracting(l -> l.qtyOnHand().intValue())
                .containsExactly(5);
        assertThat(available(federationWarehouse)).isEqualByComparingTo("5");
        assertThat(queries.movementsOf(deliveryNote, own(FEDERATION)))
                .allSatisfy(m -> assertThat(m.movementType()).isEqualTo("TRANSFER_OUT"))
                .hasSize(2);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("STOCK_POSTED", "PICK_LIST_DISPATCHED");
        assertThat(events(PickListDispatched.class)).singleElement().satisfies(e -> assertThat(e.movements())
                .isEqualTo(2));

        // A second dispatch event moves nothing.
        deliveries.onDispatched(dispatched(deliveryNote, FEDERATION), system(FEDERATION));
        assertThat(queries.movementsOf(deliveryNote, own(FEDERATION))).hasSize(2);

        // 3. grn.confirmed.v1 at the distributor: ownership passes; 10 early received, 1 damaged
        kernel.reset();
        UUID grn = Ids.next();
        grns.onGrnConfirmed(
                grnConfirmed(
                        grn,
                        DISTRIBUTOR,
                        distributorWarehouse,
                        List.of(grnLine(early, "10", "1", "1000"), grnLine(late, "5", "0", "1000"))),
                system(DISTRIBUTOR));

        List<LotBalance> received = lots(distributorWarehouse, DISTRIBUTOR);
        assertThat(received)
                .extracting(LotBalance::batchId, LotBalance::condition, l -> l.qtyOnHand()
                        .intValue())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(early, "GOOD", 9),
                        org.assertj.core.groups.Tuple.tuple(late, "GOOD", 5),
                        org.assertj.core.groups.Tuple.tuple(early, "DAMAGED", 1));
        assertThat(received).allSatisfy(l -> assertThat(l.unitCost()).isEqualByComparingTo("1000"));
        assertThat(queries.entityAverageCost(sku, own(DISTRIBUTOR)))
                .hasValueSatisfying(c -> assertThat(c.avgCost()).isEqualByComparingTo("1000"));
        assertThat(lots(distributorWarehouse, FEDERATION))
                .as("the Federation sees none of it")
                .isEmpty();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("STOCK_POSTED", "STOCK_RECEIVED");
        assertThat(events(StockMoved.class)).hasSize(3);
        assertThat(events(StockReceived.class)).singleElement().satisfies(e -> {
            assertThat(e.grnId()).isEqualTo(grn);
            assertThat(e.movements()).isEqualTo(3);
        });
        assertThat(queries.lotsConsumed(grn, own(DISTRIBUTOR))).isFalse();

        // A redelivered GRN is not received twice.
        grns.onGrnConfirmed(
                grnConfirmed(grn, DISTRIBUTOR, distributorWarehouse, List.of(grnLine(early, "10", "1", "1000"))),
                system(DISTRIBUTOR));
        assertThat(queries.movementsOf(grn, own(DISTRIBUTOR))).hasSize(3);
    }

    @Test
    void aDeliveryLargerThanTheStockIsPickedAsFarAsItGoesAndTheRestIsShort() {
        UUID deliveryNote = Ids.next();
        deliveries.onIssued(issued(deliveryNote, FEDERATION, Ids.next(), sku, late, "12"), system(FEDERATION));

        PickListView pickList = queries.pickList(deliveryNote, own(FEDERATION)).orElseThrow();
        assertThat(pickList.lines())
                .extracting(p -> p.stockLotId() != null, p -> p.qty().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(true, 10), org.assertj.core.groups.Tuple.tuple(false, 2));
        assertThat(events(PickListCreated.class)).singleElement().satisfies(e -> assertThat(e.shortQty())
                .isEqualByComparingTo("2"));
    }

    // ---- guards: nothing is committed ------------------------------------------------------

    @Test
    void aDeliveryNoteOfAnotherSellerIsRefused() {
        assertProblem(
                () -> deliveries.onIssued(
                        issued(Ids.next(), DISTRIBUTOR, Ids.next(), sku, null, "1"), system(FEDERATION)),
                "m5.delivery.seller_mismatch");
        assertNothingCommitted();
    }

    @Test
    void aDeliveryLineWithoutQuantityIsRefused() {
        assertProblem(
                () -> deliveries.onIssued(
                        issued(Ids.next(), FEDERATION, Ids.next(), sku, null, "0"), system(FEDERATION)),
                "m5.delivery.line_invalid");
        assertNothingCommitted();
    }

    @Test
    void aDispatchBeforeItsPickListIsRefused() {
        assertProblem(
                () -> deliveries.onDispatched(dispatched(Ids.next(), FEDERATION), system(FEDERATION)),
                "m5.pick_list.not_found");
        assertNothingCommitted();
    }

    @Test
    void aGrnOfAnotherReceiverIsRefused() {
        assertProblem(
                () -> grns.onGrnConfirmed(
                        grnConfirmed(
                                Ids.next(), FEDERATION, distributorWarehouse, List.of(grnLine(early, "1", "0", "1"))),
                        system(DISTRIBUTOR)),
                "m5.grn.receiver_mismatch");
        assertNothingCommitted();
    }

    @Test
    void aGrnWithoutALocationOrWithMoreDamagedThanReceivedIsRefused() {
        assertProblem(
                () -> grns.onGrnConfirmed(
                        grnConfirmed(Ids.next(), DISTRIBUTOR, null, List.of(grnLine(early, "1", "0", "1"))),
                        system(DISTRIBUTOR)),
                "m5.grn.location_required");
        assertProblem(
                () -> grns.onGrnConfirmed(
                        grnConfirmed(
                                Ids.next(), DISTRIBUTOR, distributorWarehouse, List.of(grnLine(early, "1", "2", "1"))),
                        system(DISTRIBUTOR)),
                "m5.grn.line_invalid");
        assertNothingCommitted();
    }

    // ---- the payloads, as M4's records serialise --------------------------------------------

    private JsonNode issued(UUID deliveryNote, UUID seller, UUID lineId, UUID skuId, UUID batchId, String qty) {
        Map<String, Object> line = new java.util.HashMap<>();
        line.put("lineId", lineId.toString());
        line.put("lineNo", 1);
        line.put("orderId", Ids.next().toString());
        line.put("orderLineId", Ids.next().toString());
        line.put("skuId", skuId.toString());
        line.put("batchId", batchId == null ? null : batchId.toString());
        line.put("uomCode", "EA");
        line.put("dispatchedQty", new BigDecimal(qty));
        return mapper.valueToTree(Map.of(
                "deliveryNoteId", deliveryNote.toString(),
                "docNumberDisplay", "F-DN-000001",
                "sellerEntityId", seller.toString(),
                "buyerEntityId", DISTRIBUTOR.toString(),
                "orderIds", List.of(),
                "drops",
                        List.of(Map.of(
                                "dropId", Ids.next().toString(),
                                "seq", 1,
                                "shipToLocationId", distributorWarehouse.toString(),
                                "billToEntityId", DISTRIBUTOR.toString(),
                                "orderIds", List.of(),
                                "lines", List.of(line)))));
    }

    private JsonNode dispatched(UUID deliveryNote, UUID seller) {
        return mapper.valueToTree(Map.of(
                "deliveryNoteId", deliveryNote.toString(),
                "docNumberDisplay", "F-DN-000001",
                "sellerEntityId", seller.toString(),
                "buyerEntityId", DISTRIBUTOR.toString(),
                "dispatchedAt", Instant.parse("2026-09-27T04:30:00Z").toString(),
                "vehicleRef", "WP-1234"));
    }

    private JsonNode grnConfirmed(UUID grn, UUID receiver, UUID location, List<Map<String, Object>> lines) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("grnId", grn.toString());
        payload.put("docNumberDisplay", "D-W01-GRN-000001");
        payload.put("receiverEntityId", receiver.toString());
        payload.put("receiverLocationId", location == null ? null : location.toString());
        payload.put("sellerEntityId", FEDERATION.toString());
        payload.put("confirmedAt", "2026-09-28T06:00:00Z");
        payload.put("variance", true);
        payload.put("lines", lines);
        return mapper.valueToTree(payload);
    }

    private static Map<String, Object> grnLine(UUID batch, String received, String damaged, String cost) {
        return Map.of(
                "lineId",
                Ids.next().toString(),
                "lineNo",
                1,
                "skuId",
                UUID.randomUUID().toString(),
                "batchId",
                batch.toString(),
                "uomCode",
                "EA",
                "receivedQty",
                new BigDecimal(received),
                "damagedQty",
                new BigDecimal(damaged),
                "unitCost",
                new BigDecimal(cost));
    }

    // ---- helpers --------------------------------------------------------------------------

    private static Movement receipt(UUID location, UUID batch, String qty, String cost) {
        return new Movement(
                location,
                batch,
                LotCondition.GOOD,
                MovementType.RECEIPT,
                new BigDecimal(qty),
                new BigDecimal(cost),
                null);
    }

    private BigDecimal available(UUID location) {
        return queries.availability(List.of(location), List.of(sku), own(FEDERATION))
                .get(0)
                .available();
    }

    private List<LotBalance> lots(UUID location, UUID entity) {
        return queries.balances(location, null, false, own(entity));
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private void assertNothingCommitted() {
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(superuserJdbc().queryForObject("select count(*) from inventory.pick_list", Integer.class))
                .isZero();
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String id) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(id));
    }
}
