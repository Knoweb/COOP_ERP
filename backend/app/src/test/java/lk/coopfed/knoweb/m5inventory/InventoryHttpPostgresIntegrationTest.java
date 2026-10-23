package lk.coopfed.knoweb.m5inventory;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The contract of the slice's operations (25A section 9: "every operation"), through HTTP as a
 * client calls them: the balances of a location with the FEFO rank and the cost for the owner's
 * user only; availability per location and SKU; the scope rules of the reads.
 */
class InventoryHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e677-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("0190e677-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e677-0000-7000-8000-000000000010");

    @Autowired
    TestRestTemplate http;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID warehouse;
    private UUID sku;
    private UUID batch;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5HT", "MPCS");
        warehouse = fixture.location(MPCS, "WAREHOUSE");
        sku = fixture.sku(MPCS, "SOAP");
        batch = fixture.batch(sku, MPCS, "SP1", LocalDate.of(2028, 1, 31));
        outer.run(
                own(MPCS),
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
                                        new BigDecimal("12"),
                                        new BigDecimal("45.5"),
                                        null))),
                        own(MPCS)));
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void theOwnersUserSeesTheLotWithItsRankBatchAndCost() {
        ResponseEntity<JsonNode> response = get(
                "/v1/inventory/locations/" + warehouse + "/balances",
                TestIdentityProvider.entityWideHeaders(USER, MPCS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        JsonNode lot = response.getBody().get(0);
        assertThat(lot.get("skuId").asText()).isEqualTo(sku.toString());
        assertThat(lot.get("batchId").asText()).isEqualTo(batch.toString());
        assertThat(lot.get("batchNo").asText()).startsWith("SP1");
        assertThat(lot.get("expiryDate").asText()).isEqualTo("2028-01-31");
        assertThat(lot.get("condition").asText()).isEqualTo("GOOD");
        assertThat(lot.get("qtyOnHand").decimalValue()).isEqualByComparingTo("12");
        assertThat(lot.get("fefoRank").asInt()).isEqualTo(1);
        assertThat(lot.get("negative").asBoolean()).isFalse();
        assertThat(lot.get("unitCost").decimalValue()).isEqualByComparingTo("45.5");
    }

    @Test
    void theFederationViewSeesTheLotWithoutItsCostAndAnotherEntitySeesNothing() {
        HttpHeaders view = new HttpHeaders();
        view.setBearerAuth(TestIdentityProvider.entityWideToken(USER, TEST_FEDERATION, "FEDERATION_VIEW"));
        view.set("X-Scope-Entity", TEST_FEDERATION.toString());

        JsonNode seen =
                get("/v1/inventory/locations/" + warehouse + "/balances", view).getBody();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).path("unitCost").isMissingNode()
                        || seen.get(0).path("unitCost").isNull())
                .isTrue();

        assertThat(get(
                                "/v1/inventory/locations/" + warehouse + "/balances",
                                TestIdentityProvider.entityWideHeaders(USER, OTHER))
                        .getBody())
                .isEmpty();
    }

    @Test
    void availabilityAnswersEveryLocationAndSku() {
        UUID none = fixture.sku(MPCS, "NONE");
        ResponseEntity<JsonNode> response = get(
                "/v1/inventory/availability?locationIds=" + warehouse + "&skuIds=" + sku + "," + none,
                TestIdentityProvider.entityWideHeaders(USER, MPCS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        for (JsonNode row : response.getBody()) {
            BigDecimal expected =
                    row.get("skuId").asText().equals(sku.toString()) ? new BigDecimal("12") : BigDecimal.ZERO;
            assertThat(row.get("available").decimalValue()).isEqualByComparingTo(expected);
            assertThat(row.get("locationId").asText()).isEqualTo(warehouse.toString());
        }
    }

    @Test
    void availabilityWithoutLocationsIsARequestProblem() {
        ResponseEntity<JsonNode> response =
                get("/v1/inventory/availability?skuIds=" + sku, TestIdentityProvider.entityWideHeaders(USER, MPCS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anOpeningBalanceLineCanCarryItsBatchAsCountedOverHttp() {
        fixture.entity(MPCS, "M5HT", "MPCS");
        UUID store = fixture.location(MPCS, "WAREHOUSE");

        ResponseEntity<JsonNode> prepared = post(
                "/v1/inventory/opening-balances",
                java.util.Map.of(
                        "locationId",
                        store.toString(),
                        "lines",
                        List.of(java.util.Map.of(
                                "skuId",
                                sku.toString(),
                                "batchNo",
                                "HTTP-OB-1",
                                "expiryDate",
                                "2027-12-31",
                                "printedMrp",
                                250,
                                "qty",
                                3,
                                "unitCost",
                                40))),
                USER);

        assertThat(prepared.getStatusCode())
                .as(String.valueOf(prepared.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        JsonNode line = prepared.getBody().get("lines").get(0);
        assertThat(line.get("skuId").asText()).isEqualTo(sku.toString());
        assertThat(line.get("batchId").asText()).isNotBlank();
    }

    @Test
    void anOpeningBalanceIsPreparedSignedAndCountersignedOverHttp() {
        fixture.entity(MPCS, "M5HT", "MPCS");
        UUID shop = fixture.location(MPCS, "SHOP");
        UUID signer = UUID.fromString("0190e677-0000-7000-8000-000000000011");
        UUID countersigner = UUID.fromString("0190e677-0000-7000-8000-000000000012");

        ResponseEntity<JsonNode> prepared = post(
                "/v1/inventory/opening-balances",
                java.util.Map.of(
                        "locationId", shop.toString(),
                        "lines", List.of(java.util.Map.of("batchId", batch.toString(), "qty", 6, "unitCost", 44.25))),
                USER);
        assertThat(prepared.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(prepared.getBody().get("status").asText()).isEqualTo("DRAFT");
        String id = prepared.getBody().get("openingBalanceId").asText();

        ResponseEntity<JsonNode> signed = post("/v1/inventory/opening-balances/" + id + "/sign", null, signer);
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signed.getBody().get("status").asText()).isEqualTo("SIGNED_ENTITY");

        ResponseEntity<JsonNode> refused = post("/v1/inventory/opening-balances/" + id + "/countersign", null, signer);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("m5.opening.countersigner_is_signer");

        ResponseEntity<JsonNode> posted =
                post("/v1/inventory/opening-balances/" + id + "/countersign", null, countersigner);
        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(posted.getBody().get("status").asText()).isEqualTo("POSTED");
        assertThat(posted.getBody().get("documentId").asText()).isNotBlank();

        JsonNode read = get("/v1/inventory/opening-balances/" + id, TestIdentityProvider.entityWideHeaders(USER, MPCS))
                .getBody();
        assertThat(read.get("lines")).hasSize(1);
        JsonNode lots = get(
                        "/v1/inventory/locations/" + shop + "/balances",
                        TestIdentityProvider.entityWideHeaders(USER, MPCS))
                .getBody();
        assertThat(lots).hasSize(1);
        assertThat(lots.get(0).get("qtyOnHand").decimalValue()).isEqualByComparingTo("6");
    }

    @Test
    void aTransferIsIssuedListedReadAndReceivedOverHttp() {
        UUID shop = fixture.location(MPCS, "SHOP");

        ResponseEntity<JsonNode> issued = post(
                "/v1/inventory/transfers",
                java.util.Map.of(
                        "fromLocationId", warehouse.toString(),
                        "toLocationId", shop.toString(),
                        "lines", List.of(java.util.Map.of("batchId", batch.toString(), "qty", 5))),
                USER);
        assertThat(issued.getStatusCode()).as(String.valueOf(issued.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(issued.getBody().get("status").asText()).isEqualTo("ISSUED");
        assertThat(issued.getBody().get("documentNo").asText()).contains("-XFR-");
        String id = issued.getBody().get("transferId").asText();

        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(USER, MPCS);
        JsonNode listed =
                get("/v1/inventory/transfers?locationId=" + shop, headers).getBody();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("lines").get(0).get("qty").decimalValue()).isEqualByComparingTo("5");

        ResponseEntity<JsonNode> tooSoon = post("/v1/inventory/transfers/" + id + "/receive", null, USER);
        assertThat(tooSoon.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooSoon.getBody().get("code").asText()).isEqualTo("m5.transfer.not_dispatched");

        ResponseEntity<JsonNode> dispatched = post("/v1/inventory/transfers/" + id + "/dispatch", null, USER);
        assertThat(dispatched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dispatched.getBody().get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(dispatched.getBody().get("dispatchedAt").asText()).isNotBlank();

        ResponseEntity<JsonNode> dispatchedAgain = post("/v1/inventory/transfers/" + id + "/dispatch", null, USER);
        assertThat(dispatchedAgain.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(dispatchedAgain.getBody().get("code").asText()).isEqualTo("m5.transfer.already_dispatched");

        ResponseEntity<JsonNode> received = post("/v1/inventory/transfers/" + id + "/receive", null, USER);
        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received.getBody().get("status").asText()).isEqualTo("RECEIVED");

        ResponseEntity<JsonNode> again = post("/v1/inventory/transfers/" + id + "/receive", null, USER);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(again.getBody().get("code").asText()).isEqualTo("m5.transfer.already_received");

        assertThat(get("/v1/inventory/transfers/" + id, headers)
                        .getBody()
                        .get("status")
                        .asText())
                .isEqualTo("RECEIVED");
        assertThat(get("/v1/inventory/transfers/" + Ids.next(), headers).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/inventory/locations/" + shop + "/balances", headers)
                        .getBody()
                        .get(0)
                        .get("qtyOnHand")
                        .decimalValue())
                .isEqualByComparingTo("5");
    }

    @Test
    void explicitReceiptQuantitiesAreValidatedAndRemainBackwardCompatible() {
        UUID shop = fixture.location(MPCS, "SHOP");
        UUID secondSku = fixture.sku(MPCS, "TEA1");
        UUID secondBatch = fixture.batch(secondSku, MPCS, "T1", LocalDate.of(2028, 2, 29));
        outer.run(
                own(MPCS),
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
                                        new BigDecimal("60"),
                                        null))),
                        own(MPCS)));
        ResponseEntity<JsonNode> issued = post(
                "/v1/inventory/transfers",
                java.util.Map.of(
                        "fromLocationId",
                        warehouse.toString(),
                        "toLocationId",
                        shop.toString(),
                        "lines",
                        List.of(
                                java.util.Map.of("batchId", batch.toString(), "qty", 5),
                                java.util.Map.of("batchId", secondBatch.toString(), "qty", 3))),
                USER);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = issued.getBody().get("transferId").asText();
        String lineId =
                issued.getBody().get("lines").get(0).get("transferLineId").asText();
        String secondLineId =
                issued.getBody().get("lines").get(1).get("transferLineId").asText();
        assertThat(lineId).isNotBlank();
        assertThat(secondLineId).isNotBlank();
        assertThat(post("/v1/inventory/transfers/" + id + "/dispatch", null, USER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String receiveUrl = "/v1/inventory/transfers/" + id + "/receive";
        java.util.Map<String, Object> firstLine = java.util.Map.of("transferLineId", lineId, "receivedQty", 5);
        java.util.Map<String, Object> secondLine = java.util.Map.of("transferLineId", secondLineId, "receivedQty", 3);
        kernel.reset();

        assertThat(post(receiveUrl, java.util.Map.of(), USER).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertNoReceiptSideEffects(id);
        assertThat(post(receiveUrl, java.util.Map.of("lines", List.of()), USER).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertNoReceiptSideEffects(id);
        assertThat(post(
                                receiveUrl,
                                java.util.Map.of(
                                        "lines",
                                        List.of(java.util.Map.of("transferLineId", lineId, "receivedQty", -1))),
                                USER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertNoReceiptSideEffects(id);
        assertThat(post(
                                receiveUrl,
                                java.util.Map.of(
                                        "lines",
                                        List.of(java.util.Map.of("transferLineId", lineId, "receivedQty", 5.0001))),
                                USER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertNoReceiptSideEffects(id);

        ResponseEntity<JsonNode> identicalDuplicate =
                post(receiveUrl, java.util.Map.of("lines", List.of(firstLine, firstLine, secondLine)), USER);
        assertThat(identicalDuplicate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(identicalDuplicate.getBody().get("code").asText()).isEqualTo("m5.transfer.receipt_line_duplicate");
        assertNoReceiptSideEffects(id);

        ResponseEntity<JsonNode> duplicateLineId = post(
                receiveUrl,
                java.util.Map.of(
                        "lines",
                        List.of(firstLine, java.util.Map.of("transferLineId", lineId, "receivedQty", 4), secondLine)),
                USER);
        assertThat(duplicateLineId.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(duplicateLineId.getBody().get("code").asText()).isEqualTo("m5.transfer.receipt_line_duplicate");
        assertNoReceiptSideEffects(id);

        ResponseEntity<JsonNode> nullLine =
                post(receiveUrl, java.util.Map.of("lines", java.util.Arrays.asList(firstLine, null, secondLine)), USER);
        assertThat(nullLine.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(nullLine.getBody().get("code").asText()).isEqualTo("m5.transfer.receipt_line_invalid");
        assertNoReceiptSideEffects(id);

        ResponseEntity<JsonNode> zeroReceipt = post(
                receiveUrl,
                java.util.Map.of(
                        "lines", List.of(java.util.Map.of("transferLineId", lineId, "receivedQty", 0), secondLine)),
                USER);
        assertThat(zeroReceipt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertNoReceiptSideEffects(id);

        ResponseEntity<JsonNode> received =
                post(receiveUrl, java.util.Map.of("lines", List.of(firstLine, secondLine)), USER);
        assertThat(received.getStatusCode())
                .as(String.valueOf(received.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(received.getBody().get("status").asText()).isEqualTo("RECEIVED");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select received_qty from inventory.transfer_receipt_line"
                                        + " where transfer_id = ?::uuid and transfer_line_id = ?::uuid",
                                BigDecimal.class,
                                id,
                                lineId))
                .isEqualByComparingTo("5.000");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select received_qty from inventory.transfer_receipt_line"
                                        + " where transfer_id = ?::uuid and transfer_line_id = ?::uuid",
                                BigDecimal.class,
                                id,
                                secondLineId))
                .isEqualByComparingTo("3.000");
    }

    @Test
    void aPositiveShortReceiptReturnsItsUnpostedDraftAndPostsOnlyTheActualQuantity() {
        UUID shop = fixture.location(MPCS, "SHOP");
        ResponseEntity<JsonNode> issued = post(
                "/v1/inventory/transfers",
                java.util.Map.of(
                        "fromLocationId",
                        warehouse.toString(),
                        "toLocationId",
                        shop.toString(),
                        "lines",
                        List.of(java.util.Map.of("batchId", batch.toString(), "qty", 5))),
                USER);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = issued.getBody().get("transferId").asText();
        String lineId =
                issued.getBody().get("lines").get(0).get("transferLineId").asText();
        assertThat(post("/v1/inventory/transfers/" + id + "/dispatch", null, USER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        kernel.reset();
        UUID receiptKey = UUID.randomUUID();
        ResponseEntity<JsonNode> received = post(
                "/v1/inventory/transfers/" + id + "/receive",
                java.util.Map.of("lines", List.of(java.util.Map.of("transferLineId", lineId, "receivedQty", 3.5))),
                USER,
                receiptKey);

        assertThat(received.getStatusCode())
                .as(String.valueOf(received.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(received.getBody().get("status").asText()).isEqualTo("RECEIVED");
        assertThat(received.getBody().get("shortfallId").asText()).isNotBlank();
        assertThat(received.getBody().get("shortfallStatus").asText()).isEqualTo("DRAFT");
        assertThat(received.getBody().get("shortfallCategory").asText()).isEqualTo("DAMAGED_IN_TRANSIT");
        assertThat(received.getBody().get("lines").get(0).get("receivedQty").decimalValue())
                .isEqualByComparingTo("3.500");
        assertThat(received.getBody().get("lines").get(0).get("shortfallQty").decimalValue())
                .isEqualByComparingTo("1.500");
        ResponseEntity<JsonNode> retried = post(
                "/v1/inventory/transfers/" + id + "/receive",
                java.util.Map.of("lines", List.of(java.util.Map.of("transferLineId", lineId, "receivedQty", 3.5))),
                USER,
                receiptKey);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().get("shortfallId").asText())
                .isEqualTo(received.getBody().get("shortfallId").asText());
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select qty_delta from inventory.stock_movement"
                                        + " where document_id = ?::uuid and movement_type = 'TRANSFER_IN'",
                                BigDecimal.class,
                                id))
                .isEqualByComparingTo("3.500");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement where document_id = ?::uuid"
                                        + " and movement_type in ('COUNT_ADJUST', 'WRITE_OFF')",
                                Integer.class,
                                id))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_shortfall where transfer_id = ?::uuid",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_shortfall_line where transfer_id = ?::uuid",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(kernel.committedAudit())
                .extracting(lk.coopfed.knoweb.testsupport.KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "TRANSFER_RECEIVED", "TRANSFER_SHORTFALL_DRAFTED");
    }

    @Test
    void theStockCardListsTheMovementsInLedgerOrderWithTheRunningQuantity() {
        UUID shop = fixture.location(MPCS, "SHOP");
        ResponseEntity<JsonNode> issued = post(
                "/v1/inventory/transfers",
                java.util.Map.of(
                        "fromLocationId", warehouse.toString(),
                        "toLocationId", shop.toString(),
                        "lines", List.of(java.util.Map.of("batchId", batch.toString(), "qty", 5))),
                USER);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String url = "/v1/inventory/locations/" + warehouse + "/movements?skuId=" + sku;

        ResponseEntity<JsonNode> card = get(url, TestIdentityProvider.entityWideHeaders(USER, MPCS));

        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(card.getBody()).hasSize(2);
        JsonNode first = card.getBody().get(0);
        assertThat(first.get("movement").get("movementType").asText()).isEqualTo("RECEIPT");
        assertThat(first.get("movement").get("unitCostAtMovement").decimalValue())
                .isEqualByComparingTo("45.5");
        assertThat(first.get("balanceAfter").decimalValue()).isEqualByComparingTo("12");
        JsonNode second = card.getBody().get(1);
        assertThat(second.get("movement").get("movementType").asText()).isEqualTo("TRANSFER_OUT");
        assertThat(second.get("movement").get("qtyDelta").decimalValue()).isEqualByComparingTo("-5");
        assertThat(second.get("balanceAfter").decimalValue()).isEqualByComparingTo("7");

        HttpHeaders view = new HttpHeaders();
        view.setBearerAuth(TestIdentityProvider.entityWideToken(USER, TEST_FEDERATION, "FEDERATION_VIEW"));
        view.set("X-Scope-Entity", TEST_FEDERATION.toString());
        JsonNode seen = get(url, view).getBody();
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).get("movement").path("unitCostAtMovement").isMissingNode()
                        || seen.get(0)
                                .get("movement")
                                .path("unitCostAtMovement")
                                .isNull())
                .isTrue();

        assertThat(get(url, TestIdentityProvider.entityWideHeaders(USER, OTHER)).getBody())
                .isEmpty();
    }

    @Test
    void theStockCardWithoutASkuIsARequestProblem() {
        ResponseEntity<JsonNode> response = get(
                "/v1/inventory/locations/" + warehouse + "/movements",
                TestIdentityProvider.entityWideHeaders(USER, MPCS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theReceiptOfAGrnAndThePickListOfADeliveryNoteAreRead() {
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(USER, MPCS);

        assertThat(get("/v1/inventory/receipts/" + Ids.next(), headers).getBody())
                .isEmpty();
        assertThat(get("/v1/inventory/pick-lists/" + Ids.next(), headers).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/inventory/opening-balances/" + Ids.next(), headers).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String url, Object body, UUID user) {
        return post(url, body, user, UUID.randomUUID());
    }

    private ResponseEntity<JsonNode> post(String url, Object body, UUID user, UUID idempotencyKey) {
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(user, MPCS);
        headers.set("Idempotency-Key", idempotencyKey.toString());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void assertNoReceiptSideEffects(String transferId) {
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_receipt where transfer_id = ?::uuid",
                                Integer.class,
                                transferId))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.transfer_receipt_line where transfer_id = ?::uuid",
                                Integer.class,
                                transferId))
                .isZero();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement"
                                        + " where document_id = ?::uuid and movement_type = 'TRANSFER_IN'",
                                Integer.class,
                                transferId))
                .isZero();
    }
}
