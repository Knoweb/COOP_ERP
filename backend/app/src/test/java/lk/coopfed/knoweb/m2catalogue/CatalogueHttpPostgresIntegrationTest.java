package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class CatalogueHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e620-0000-7000-8000-000000000002");

    private static final UUID USER = UUID.fromString("0190e620-0000-7000-8000-000000000010");

    private static final UUID TAX_CATEGORY = UUID.fromString("0190e620-0000-7000-8000-000000000100");

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void cleanCatalogue() {
        JdbcTemplate admin = superuserJdbc();

        admin.execute("truncate table catalogue.sku cascade");

        admin.update(
                """
                insert into catalogue.uom
                    (uom_code, name_en, is_weight)
                values
                    ('EA', 'Each', false)
                on conflict do nothing
                """);

        admin.update(
                """
                insert into catalogue.tax_category
                    (tax_category_id, code, name_en, owner_entity_id)
                values
                    (?, 'M2HTTP', 'M2 HTTP tax', ?)
                on conflict do nothing
                """,
                TAX_CATEGORY,
                MPCS);

        kernel.reset();
    }

    @AfterEach
    void removeTestTaxCategory() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX_CATEGORY);
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void skuHttpLifecycleAndSearchFollowTheGeneratedContract() {
        HttpHeaders headers = headers();

        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<JsonNode> created = http.exchange(
                "/v1/catalogue/skus",
                HttpMethod.POST,
                new HttpEntity<>(skuBody("HTTP fresh milk", "HTTP කිරි", "HTTP பால்"), headers),
                JsonNode.class);

        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);

        JsonNode createdBody = created.getBody();
        assertThat(createdBody).isNotNull();
        assertThat(createdBody.get("status").asText()).isEqualTo("DRAFT");
        assertThat(createdBody.get("nameEn").asText()).isEqualTo("HTTP fresh milk");
        assertThat(createdBody.get("skuCode").asText()).startsWith("SKU-");

        String skuId = createdBody.get("skuId").asText();

        ResponseEntity<JsonNode> fetched =
                http.exchange("/v1/catalogue/skus/" + skuId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("skuId").asText()).isEqualTo(skuId);

        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<Void> updated = http.exchange(
                "/v1/catalogue/skus/" + skuId,
                HttpMethod.PATCH,
                new HttpEntity<>(skuBody("HTTP fresh milk updated", "HTTP කිරි", "HTTP பால்"), headers),
                Void.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("SKU_UPDATED");

        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<Void> activated = http.exchange(
                "/v1/catalogue/skus/" + skuId + "/activate",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("target", "LOCAL"), headers),
                Void.class);

        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> active =
                http.exchange("/v1/catalogue/skus/" + skuId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(active.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(active.getBody().get("status").asText()).isEqualTo("LOCAL");
        assertThat(active.getBody().get("nameEn").asText()).isEqualTo("HTTP fresh milk updated");

        ResponseEntity<JsonNode> search = http.exchange(
                "/v1/catalogue/skus?q=fresh&lang=en&offset=0&limit=20",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);

        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(search.getBody().get("items")).hasSize(1);
        assertThat(search.getBody().get("items").get(0).get("skuId").asText()).isEqualTo(skuId);

        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<Void> deactivated = http.exchange(
                "/v1/catalogue/skus/" + skuId + "/deactivate",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "reasonCode", "SEASONAL",
                                "reasonText", "HTTP contract test"),
                        headers),
                Void.class);

        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> inactive =
                http.exchange("/v1/catalogue/skus/" + skuId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(inactive.getBody().get("status").asText()).isEqualTo("INACTIVE");

        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<Void> reactivated = http.exchange(
                "/v1/catalogue/skus/" + skuId + "/reactivate",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "reasonCode", "RETURNED",
                                "reasonText", "HTTP contract test"),
                        headers),
                Void.class);

        assertThat(reactivated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> restored =
                http.exchange("/v1/catalogue/skus/" + skuId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(restored.getBody().get("status").asText()).isEqualTo("LOCAL");
    }

    @Test
    void conversionsBarcodesAndTheLookupFollowTheGeneratedContract() {
        HttpHeaders headers = headers();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        JsonNode created = http.exchange(
                        "/v1/catalogue/skus",
                        HttpMethod.POST,
                        new HttpEntity<>(skuBody("HTTP milk powder", null, null), headers),
                        JsonNode.class)
                .getBody();
        String skuId = created.get("skuId").asText();

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        http.exchange(
                "/v1/catalogue/skus/" + skuId + "/activate",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("target", "LOCAL"), headers),
                Void.class);

        superuserJdbc()
                .update("insert into catalogue.uom (uom_code, name_en, is_weight) values ('CASE', 'Case', false)"
                        + " on conflict do nothing");
        UUID batchId = UUID.randomUUID();
        superuserJdbc()
                .update(
                        "insert into catalogue.batch (batch_id, sku_id, batch_no, owner_entity_id) values (?, ?, 'H1', ?)",
                        batchId,
                        UUID.fromString(skuId),
                        MPCS);
        kernel.reset();

        // POST /skus/{skuId}/conversions
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> conversion = http.exchange(
                "/v1/catalogue/skus/" + skuId + "/conversions",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("uomCode", "CASE", "factorToBase", 24, "effectiveFrom", "2026-01-01"), headers),
                JsonNode.class);
        assertThat(conversion.getStatusCode())
                .as(String.valueOf(conversion.getBody()))
                .isEqualTo(HttpStatus.NO_CONTENT);

        // A broken rule is 422 with its message id.
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> overlap = http.exchange(
                "/v1/catalogue/skus/" + skuId + "/conversions",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("uomCode", "CASE", "factorToBase", 12, "effectiveFrom", "2025-06-01"), headers),
                JsonNode.class);
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(overlap.getBody().get("code").asText()).isEqualTo("m2.conversion.overlap");

        // POST /skus/{skuId}/barcodes: a case code with a batch
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> registered = http.exchange(
                "/v1/catalogue/skus/" + skuId + "/barcodes",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("barcode", "4791234567891", "symbology", "EAN13", "uomCode", "CASE"), headers),
                JsonNode.class);
        assertThat(registered.getStatusCode())
                .as(String.valueOf(registered.getBody()))
                .isEqualTo(HttpStatus.NO_CONTENT);

        // The slice's shape is checked by the kernel: an unknown symbology is 400.
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> badSymbology = http.exchange(
                "/v1/catalogue/skus/" + skuId + "/barcodes",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("barcode", "4791234567891", "symbology", "CODE128", "uomCode", "EA"), headers),
                JsonNode.class);
        assertThat(badSymbology.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // POST /skus/{skuId}/barcodes/{barcode}/link
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> linked = http.exchange(
                "/v1/catalogue/skus/" + skuId + "/barcodes/4791234567891/link",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("symbology", "EAN13", "batchId", batchId.toString()), headers),
                JsonNode.class);
        assertThat(linked.getStatusCode()).as(String.valueOf(linked.getBody())).isEqualTo(HttpStatus.NO_CONTENT);

        // GET /lookup: the item card with the unit's factor and the linked batch
        ResponseEntity<JsonNode> lookup = http.exchange(
                "/v1/catalogue/lookup?barcode=4791234567891&symbology=EAN13",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);
        assertThat(lookup.getStatusCode()).as(String.valueOf(lookup.getBody())).isEqualTo(HttpStatus.OK);
        JsonNode card = lookup.getBody();
        assertThat(card.get("skuId").asText()).isEqualTo(skuId);
        assertThat(card.get("uomCode").asText()).isEqualTo("CASE");
        assertThat(card.get("factorToBase").decimalValue()).isEqualByComparingTo("24");
        assertThat(card.get("fallback").get("si").asBoolean()).isTrue();
        assertThat(card.get("nameSi").asText()).isEqualTo("HTTP milk powder");
        assertThat(card.get("batch").get("batchId").asText()).isEqualTo(batchId.toString());
        assertThat(card.get("batch").get("batchNo").asText()).isEqualTo("H1");
        assertThat(card.get("sellThrough").asBoolean()).isFalse();

        // DELETE /skus/{skuId}/barcodes/{barcode}?symbology=...&reasonCode=...
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> retired = http.exchange(
                "/v1/catalogue/skus/" + skuId + "/barcodes/4791234567891?symbology=EAN13&reasonCode=WRONG_PACK",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                JsonNode.class);
        assertThat(retired.getStatusCode())
                .as(String.valueOf(retired.getBody()))
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> gone = http.exchange(
                "/v1/catalogue/lookup?barcode=4791234567891",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gone.getBody().get("code").asText()).isEqualTo("m2.barcode.not_found");

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsOnlyOnce("CONVERSION_DEFINED", "BARCODE_REGISTERED", "BARCODE_LINKED", "BARCODE_RETIRED");
    }

    @Test
    void aRepeatedCreateWithTheSameKeyAnswersTheFirstSkuAndCreatesNothingMore() {
        HttpHeaders headers = headers();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(skuBody("Replayed milk", null, null), headers);

        ResponseEntity<JsonNode> first = http.exchange("/v1/catalogue/skus", HttpMethod.POST, request, JsonNode.class);
        ResponseEntity<JsonNode> again = http.exchange("/v1/catalogue/skus", HttpMethod.POST, request, JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("skuId").asText())
                .isEqualTo(first.getBody().get("skuId").asText());
        assertThat(superuserJdbc().queryForObject("select count(*) from catalogue.sku", Integer.class))
                .isEqualTo(1);
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsOnlyOnce("SKU_CREATED");
    }

    @Test
    void aSkuThatIsNotThereIsANotFoundProblem() {
        ResponseEntity<JsonNode> missing = http.exchange(
                "/v1/catalogue/skus/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers()), JsonNode.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code").asText()).isEqualTo("m2.sku.not_found");
        assertThat(missing.getBody().get("title").asText()).isNotBlank();
    }

    @Test
    void mutationWithoutIdempotencyKeyIsRejectedByTheHttpContract() {
        HttpHeaders headers = headers();
        headers.remove("Idempotency-Key");

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/catalogue/skus",
                HttpMethod.POST,
                new HttpEntity<>(skuBody("No key", null, null), headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(USER, MPCS));
        headers.set("X-Scope-Entity", MPCS.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static Map<String, Object> skuBody(String en, String si, String ta) {

        Map<String, Object> body = new java.util.LinkedHashMap<>();

        body.put("nameEn", en);

        if (si != null) {
            body.put("nameSi", si);
        }

        if (ta != null) {
            body.put("nameTa", ta);
        }

        body.put("baseUomCode", "EA");
        body.put("soldByWeight", false);
        body.put("batchTracked", false);
        body.put("expiryTracked", false);
        body.put("hasPrintedMrp", false);
        body.put("taxCategoryId", TAX_CATEGORY.toString());
        body.put("multiMrpPolicy", "AUTO_LOWEST");
        body.put("originKind", "PURCHASED");
        body.put("attributes", Map.of("source", "http-test"));

        return body;
    }
}
