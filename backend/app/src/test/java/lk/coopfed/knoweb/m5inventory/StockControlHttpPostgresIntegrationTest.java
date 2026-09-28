package lk.coopfed.knoweb.m5inventory;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Every operation of stock control through HTTP as a client calls it (25A section 9, "every
 * operation"): a count scheduled, started, submitted and its adjustment approved (and another
 * rejected); a negative lot listed and acknowledged; a write-off drafted, photographed, submitted,
 * witnessed and approved (and another rejected); a recipe defined and retired; a repack executed,
 * read and reversed; a request problem.
 */
@Import(MemoryObjectStore.class)
class StockControlHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67f-0000-7000-8000-000000000002");
    private static final UUID CLERK = UUID.fromString("0190e67f-0000-7000-8000-000000000010");
    private static final UUID MANAGER = UUID.fromString("0190e67f-0000-7000-8000-000000000011");

    @Autowired
    TestRestTemplate http;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID stores;
    private UUID rice;
    private UUID riceBatch;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5HC", "MPCS");
        stores = fixture.location(MPCS, "WAREHOUSE");
        rice = fixture.sku(MPCS, "RICE5");
        riceBatch = fixture.batch(rice, MPCS, "R1", LocalDate.of(2027, 3, 31));
        post(stores, riceBatch, MovementType.RECEIPT, "50", "100");
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void aCountIsScheduledStartedSubmittedAndItsAdjustmentApprovedOrRejected() {
        ResponseEntity<JsonNode> scheduled = post(
                "/v1/inventory/counts",
                Map.of("locationId", stores, "scopeKind", "SKUS", "skuIds", List.of(rice)),
                CLERK);
        assertThat(scheduled.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String task = scheduled.getBody().get("taskId").asText();
        assertThat(scheduled.getBody().get("status").asText()).isEqualTo("SCHEDULED");

        JsonNode started =
                post("/v1/inventory/counts/" + task + "/start", null, CLERK).getBody();
        assertThat(started.get("status").asText()).isEqualTo("COUNTING");
        assertThat(started.get("expectation")).singleElement().satisfies(e -> {
            assertThat(e.get("batchNo").asText()).startsWith("R1");
            assertThat(e.get("expectedQty").decimalValue()).isEqualByComparingTo("50");
        });

        ResponseEntity<JsonNode> problem = post(
                "/v1/inventory/counts/" + task + "/submit",
                Map.of("lines", List.of(Map.of("batchId", riceBatch, "countedQty", -1))),
                CLERK);
        assertThat(problem.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode submitted = post(
                        "/v1/inventory/counts/" + task + "/submit",
                        Map.of("lines", List.of(Map.of("batchId", riceBatch, "countedQty", 44))),
                        CLERK)
                .getBody();
        assertThat(submitted.get("status").asText()).isEqualTo("VARIANCE_REVIEW");
        assertThat(submitted.get("reviewValue").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(submitted.get("lines")).singleElement().satisfies(l -> {
            assertThat(l.get("varianceQty").decimalValue()).isEqualByComparingTo("-6");
            assertThat(l.get("withinTolerance").asBoolean()).isFalse();
        });

        ResponseEntity<JsonNode> refused = post("/v1/inventory/counts/" + task + "/approve", null, CLERK);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        JsonNode approved =
                post("/v1/inventory/counts/" + task + "/approve", null, MANAGER).getBody();
        assertThat(approved.get("status").asText()).isEqualTo("CLOSED");
        assertThat(approved.get("outcome").asText()).isEqualTo("APPROVED");
        assertThat(get("/v1/inventory/counts?locationId=" + stores).getBody()).hasSize(1);
        assertThat(get("/v1/inventory/counts/" + task)
                        .getBody()
                        .get("reviewedBy")
                        .asText())
                .isEqualTo(MANAGER.toString());
        assertThat(get("/v1/inventory/counts/" + Ids.next()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String second = post("/v1/inventory/counts", Map.of("locationId", stores, "scopeKind", "FULL"), CLERK)
                .getBody()
                .get("taskId")
                .asText();
        post("/v1/inventory/counts/" + second + "/start", null, CLERK);
        post(
                "/v1/inventory/counts/" + second + "/submit",
                Map.of("lines", List.of(Map.of("batchId", riceBatch, "countedQty", 30))),
                CLERK);
        JsonNode rejected = post("/v1/inventory/counts/" + second + "/reject", Map.of("reason", "Count again"), MANAGER)
                .getBody();
        assertThat(rejected.get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(onHand()).isEqualByComparingTo("44");
    }

    @Test
    void aNegativeLotIsListedAndAcknowledged() {
        post(stores, riceBatch, MovementType.SALE, "-53", null);

        JsonNode lots =
                get("/v1/inventory/locations/" + stores + "/negative-lots").getBody();
        assertThat(lots)
                .singleElement()
                .satisfies(l -> assertThat(l.get("qtyOnHand").decimalValue()).isEqualByComparingTo("-3"));
        String lot = lots.get(0).get("stockLotId").asText();

        ResponseEntity<JsonNode> acknowledged = post(
                "/v1/inventory/lots/" + lot + "/acknowledge-negative", Map.of("reason", "Oversold offline"), CLERK);
        assertThat(acknowledged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acknowledged.getBody().get("acknowledgedAt").isNull()).isFalse();
    }

    @Test
    void aWriteOffGoesFromDraftToPostedAndAnotherIsRejected() {
        ResponseEntity<JsonNode> drafted = post(
                "/v1/inventory/write-offs",
                Map.of(
                        "locationId",
                        stores,
                        "category",
                        "THEFT",
                        "lines",
                        List.of(Map.of("batchId", riceBatch, "qty", 2))),
                CLERK);
        assertThat(drafted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(drafted.getBody().get("photosRequired").asBoolean()).isTrue();
        String id = drafted.getBody().get("writeOffId").asText();

        assertThat(post("/v1/inventory/write-offs/" + id + "/submit", null, CLERK)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode upload = post(
                        "/v1/inventory/write-offs/" + id + "/photos",
                        Map.of("contentType", "image/jpeg", "contentLength", 1024),
                        CLERK)
                .getBody();
        assertThat(upload.get("url").asText()).isNotBlank();

        JsonNode submitted =
                post("/v1/inventory/write-offs/" + id + "/submit", null, CLERK).getBody();
        assertThat(submitted.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(submitted.get("documentNo").asText()).isNotBlank();
        superuserJdbc()
                .update(
                        "update kernel.document_attachment set status = 'COMPLETE' where attachment_id = ?::uuid",
                        upload.get("attachmentId").asText());

        JsonNode witnessed = post("/v1/inventory/write-offs/" + id + "/witness", null, MANAGER)
                .getBody();
        assertThat(witnessed.get("status").asText()).isEqualTo("WITNESSED");
        JsonNode posted = post("/v1/inventory/write-offs/" + id + "/approve", null, MANAGER)
                .getBody();
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");
        assertThat(posted.get("value").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(posted.get("photos"))
                .singleElement()
                .satisfies(p -> assertThat(p.get("status").asText()).isEqualTo("COMPLETE"));
        assertThat(onHand()).isEqualByComparingTo("48");

        String other = post(
                        "/v1/inventory/write-offs",
                        Map.of(
                                "locationId",
                                stores,
                                "category",
                                "EXPIRED",
                                "lines",
                                List.of(Map.of("batchId", riceBatch, "qty", 1))),
                        CLERK)
                .getBody()
                .get("writeOffId")
                .asText();
        post("/v1/inventory/write-offs/" + other + "/submit", null, CLERK);
        JsonNode rejected = post("/v1/inventory/write-offs/" + other + "/reject", Map.of("reason", "In date"), MANAGER)
                .getBody();
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(get("/v1/inventory/write-offs?locationId=" + stores).getBody())
                .hasSize(2);
        assertThat(get("/v1/inventory/write-offs/" + other)
                        .getBody()
                        .get("rejectReason")
                        .asText())
                .isEqualTo("In date");
        assertThat(get("/v1/inventory/write-offs/" + Ids.next()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRecipeRepacksLooseRiceAndTheRepackIsReversed() {
        UUID loose = fixture.plainSku(MPCS, "RICE LOOSE", "PURCHASED");
        UUID looseBatch = fixture.batch(loose, MPCS, "L1", null);
        post(stores, looseBatch, MovementType.RECEIPT, "20", "200");
        UUID pack = fixture.plainSku(MPCS, "RICE 5KG PACK", "REPACK_OUTPUT");

        ResponseEntity<JsonNode> defined = post(
                "/v1/inventory/recipes",
                Map.of(
                        "name",
                        "Loose rice into 5 kg packs",
                        "inputSkuId",
                        loose,
                        "inputQty",
                        5,
                        "outputSkuId",
                        pack,
                        "outputQty",
                        1),
                CLERK);
        assertThat(defined.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String recipe = defined.getBody().get("recipeId").asText();
        assertThat(get("/v1/inventory/recipes").getBody()).hasSize(1);

        ResponseEntity<JsonNode> executed = post(
                "/v1/inventory/repacks",
                Map.of(
                        "recipeId", recipe,
                        "locationId", stores,
                        "inputBatchId", looseBatch,
                        "inputQty", 10,
                        "actualOutputQty", 2),
                CLERK);
        assertThat(executed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode repack = executed.getBody();
        assertThat(repack.get("outputUnitCost").decimalValue()).isEqualByComparingTo("1000");
        assertThat(repack.get("outputBatchNo").asText()).startsWith("S-RPK-");
        String id = repack.get("repackId").asText();
        assertThat(get("/v1/inventory/repacks?locationId=" + stores).getBody()).hasSize(1);
        assertThat(get("/v1/inventory/repacks/" + id).getBody().get("status").asText())
                .isEqualTo("EXECUTED");

        JsonNode reversed = post("/v1/inventory/repacks/" + id + "/reverse", Map.of("reason", "Wrong pack"), MANAGER)
                .getBody();
        assertThat(reversed.get("status").asText()).isEqualTo("REVERSED");

        JsonNode retired =
                post("/v1/inventory/recipes/" + recipe + "/retire", null, CLERK).getBody();
        assertThat(retired.get("status").asText()).isEqualTo("RETIRED");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void post(UUID location, UUID batch, MovementType type, String qty, String cost) {
        outer.run(
                own(MPCS),
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        location,
                                        batch,
                                        LotCondition.GOOD,
                                        type,
                                        new BigDecimal(qty),
                                        cost == null ? null : new BigDecimal(cost),
                                        null))),
                        own(MPCS)));
    }

    private BigDecimal onHand() {
        return get("/v1/inventory/locations/" + stores + "/balances?skuId=" + rice)
                .getBody()
                .get(0)
                .get("qtyOnHand")
                .decimalValue();
    }

    private ResponseEntity<JsonNode> get(String url) {
        return http.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.entityWideHeaders(CLERK, MPCS)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String url, Object body, UUID user) {
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(user, MPCS);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
