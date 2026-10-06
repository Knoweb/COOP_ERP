package lk.coopfed.knoweb.m5inventory.internal.consumers;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.system;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockReturnedToSeller;
import lk.coopfed.knoweb.m5inventory.api.TransferIssued;
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

/**
 * The consumers of M4-06 and M4-10 (25A section 6.2): an approved transfer request becomes a
 * transfer from the society's stores to the shop, picked first-expiry-first and naming the request;
 * the goods of an approved claim that the buyer sends back leave its lot as RETURN_TO_SELLER. Driven
 * by the payloads M4's records publish (TransferRequestApproved, ClaimReturnDispatched), delivered
 * as the consumer framework delivers them: in the OWN scope of the event's owner, with no user.
 */
class RequestAndReturnConsumersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SOCIETY = UUID.fromString("0190e678-0000-7000-8000-000000000004");
    private static final UUID OTHER = UUID.fromString("0190e678-0000-7000-8000-000000000005");

    @Autowired
    TransferRequestConsumer requests;

    @Autowired
    ClaimReturnConsumer returns;

    @Autowired
    InventoryQueries queries;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay businessDay;

    private InventoryFixture fixture;
    private UUID stores;
    private UUID shop;
    private UUID sku;
    private UUID early;
    private UUID late;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        stores = fixture.location(SOCIETY, "WAREHOUSE");
        shop = fixture.location(SOCIETY, "SHOP");
        sku = fixture.sku(SOCIETY, "SUGAR1");
        early = fixture.batch(sku, SOCIETY, "E1", LocalDate.of(2027, 3, 31));
        late = fixture.batch(sku, SOCIETY, "L1", LocalDate.of(2027, 9, 30));
        outer.run(
                own(SOCIETY),
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(receipt(stores, early, "4"), receipt(stores, late, "10"))),
                        own(SOCIETY)));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void anApprovedRequestIsIssuedFirstExpiryFirstAndNamesTheRequest() {
        UUID requestId = Ids.next();

        requests.onApproved(approved(requestId, "6"), system(SOCIETY));

        TransferView transfer =
                queries.transferOfRequest(requestId, own(SOCIETY)).orElseThrow();
        assertThat(transfer.fromLocationId()).isEqualTo(stores);
        assertThat(transfer.toLocationId()).isEqualTo(shop);
        assertThat(transfer.status()).isEqualTo("IN_TRANSIT");
        assertThat(transfer.lines())
                .extracting(TransferView.Line::batchId, line -> line.qty().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(early, 4), org.assertj.core.groups.Tuple.tuple(late, 2));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("TRANSFER_ISSUED");
        assertThat(events(TransferIssued.class))
                .singleElement()
                .satisfies(event -> assertThat(event.transferRequestId()).isEqualTo(requestId));

        // A redelivery of the approval issues nothing more.
        kernel.reset();
        requests.onApproved(approved(requestId, "6"), system(SOCIETY));
        assertThat(queries.transfers(stores, own(SOCIETY))).hasSize(1);
        assertThat(events(TransferIssued.class)).isEmpty();
    }

    /** Wave 2, M5-03: a request is filled once; what could not be sent is flagged and counted, never dropped silently. */
    @Test
    void aRequestThatCannotBeFilledInFullIsSentShortAndTheShortLinesAreFlagged() {
        UUID requestId = Ids.next();
        UUID salt = fixture.sku(SOCIETY, "SALT1");
        JsonNode payload = mapper.valueToTree(Map.of(
                "requestId", requestId,
                "ownerEntityId", SOCIETY,
                "fromLocationId", stores,
                "toLocationId", shop,
                "lines",
                        List.of(
                                Map.of("lineId", Ids.next(), "skuId", sku, "qty", new BigDecimal("20")),
                                Map.of("lineId", Ids.next(), "skuId", salt, "qty", new BigDecimal("5")))));

        requests.onApproved(payload, system(SOCIETY));

        TransferView transfer =
                queries.transferOfRequest(requestId, own(SOCIETY)).orElseThrow();
        assertThat(transfer.lines())
                .extracting(TransferView.Line::batchId, line -> line.qty().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(early, 4), org.assertj.core.groups.Tuple.tuple(late, 10));
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("TRANSFER_REQUEST_SHORT"))
                .singleElement()
                .satisfies(a -> assertThat(String.valueOf(a.after()))
                        .contains(sku.toString(), "wanted=20", "sent=14", salt.toString(), "wanted=5", "sent=0"));
        assertThat(events(TransferIssued.class)).singleElement().satisfies(event -> {
            assertThat(event.transferRequestId()).isEqualTo(requestId);
            assertThat(event.shortLines()).isEqualTo(2);
        });
    }

    @Test
    void anApprovedRequestNeverSendsAnExpiredLot() {
        LocalDate today = businessDay.today();
        UUID gone = fixture.batch(sku, SOCIETY, "G1", today.minusDays(2));
        outer.run(
                own(SOCIETY),
                () -> ledger.post(
                        new PostMovements(Ids.next(), null, null, List.of(receipt(stores, gone, "30"))), own(SOCIETY)));
        kernel.reset();
        UUID requestId = Ids.next();

        requests.onApproved(approved(requestId, "6"), system(SOCIETY));
        assumeTrue(today.equals(businessDay.today()), "the run crossed midnight in Colombo");

        assertThat(queries.transferOfRequest(requestId, own(SOCIETY))
                        .orElseThrow()
                        .lines())
                .extracting(TransferView.Line::batchId)
                .containsExactly(early, late);
    }

    @Test
    void aRequestWithNothingLeftToSendFails() {
        UUID requestId = Ids.next();
        UUID emptySku = fixture.sku(SOCIETY, "SALT1");
        JsonNode payload = mapper.valueToTree(Map.of(
                "requestId", requestId,
                "ownerEntityId", SOCIETY,
                "fromLocationId", stores,
                "toLocationId", shop,
                "lines", List.of(Map.of("lineId", Ids.next(), "skuId", emptySku, "qty", new BigDecimal("1")))));

        assertThatThrownBy(() -> requests.onApproved(payload, system(SOCIETY)))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m5.transfer.insufficient_stock"));
        assertThat(queries.transferOfRequest(requestId, own(SOCIETY))).isEmpty();
    }

    @Test
    void theGoodsOfAnApprovedClaimLeaveTheBuyersLotOnce() {
        UUID claimId = Ids.next();

        returns.onReturnDispatched(returned(claimId, SOCIETY, "3"), system(SOCIETY));

        assertThat(lot(stores, late).qtyOnHand()).isEqualByComparingTo("7");
        assertThat(queries.movementsOf(claimId, own(SOCIETY)))
                .singleElement()
                .satisfies(movement -> assertThat(movement.movementType()).isEqualTo("RETURN_TO_SELLER"));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_RETURNED_TO_SELLER");
        assertThat(events(StockReturnedToSeller.class)).singleElement().satisfies(event -> {
            assertThat(event.claimId()).isEqualTo(claimId);
            assertThat(event.qty()).isEqualByComparingTo("3");
        });

        kernel.reset();
        returns.onReturnDispatched(returned(claimId, SOCIETY, "3"), system(SOCIETY));
        assertThat(lot(stores, late).qtyOnHand()).isEqualByComparingTo("7");
        assertThat(kernel.committedEvents()).isEmpty();

        // Applied in the buyer's own scope only.
        assertThatThrownBy(() -> returns.onReturnDispatched(returned(Ids.next(), SOCIETY, "1"), system(OTHER)))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.claim.buyer_mismatch"));
    }

    private JsonNode approved(UUID requestId, String qty) {
        return mapper.valueToTree(Map.of(
                "requestId", requestId,
                "ownerEntityId", SOCIETY,
                "fromLocationId", stores,
                "toLocationId", shop,
                "lines", List.of(Map.of("lineId", Ids.next(), "skuId", sku, "qty", new BigDecimal(qty)))));
    }

    private JsonNode returned(UUID claimId, UUID buyer, String qty) {
        return mapper.valueToTree(Map.of(
                "claimId", claimId,
                "buyerEntityId", buyer,
                "locationId", stores,
                "lines", List.of(Map.of("claimLineId", Ids.next(), "batchId", late, "qty", new BigDecimal(qty)))));
    }

    private LotBalance lot(UUID location, UUID batch) {
        return queries.balances(location, null, true, own(SOCIETY)).stream()
                .filter(balance -> batch.equals(balance.batchId()) && "GOOD".equals(balance.condition()))
                .findFirst()
                .orElseThrow();
    }

    private static Movement receipt(UUID location, UUID batch, String qty) {
        return new Movement(
                location,
                batch,
                LotCondition.GOOD,
                MovementType.RECEIPT,
                new BigDecimal(qty),
                new BigDecimal("100"),
                null);
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
