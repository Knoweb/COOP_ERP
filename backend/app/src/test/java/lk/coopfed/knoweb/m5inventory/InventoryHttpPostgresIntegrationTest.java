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
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(user, MPCS);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
