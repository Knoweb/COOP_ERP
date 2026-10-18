package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m2catalogue.api.DuplicateSuspectResolved;
import lk.coopfed.knoweb.m2catalogue.api.DuplicateSuspected;
import lk.coopfed.knoweb.m2catalogue.api.SkusBulkImported;
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

/**
 * Integration tests for the Catalogue Steward Bulk Import Service (CAT-01, 22A section 5, section 6).
 *
 * <p>Validates:
 * <ul>
 *   <li>Row validation and duplicate suspect detection in VALIDATE mode without commits.</li>
 *   <li>Bulk import execution in COMMIT mode with transliteration, barcode registration, and duplicate flagging.</li>
 *   <li>Single transaction boundary, audit records, and domain event publication.</li>
 *   <li>High-volume batch import performance (~1,000 SKUs via JDBC batching).</li>
 *   <li>Duplicate suspect lifecycle query and resolution (MERGED / DISMISSED).</li>
 * </ul>
 */
class BulkImportSkusIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e620-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e620-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e620-0000-7000-8000-000000000100");

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void cleanCatalogue() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku, catalogue.supplier, catalogue.duplicate_suspect cascade");

        admin.update(
                """
                insert into catalogue.uom
                    (uom_code, name_en, is_weight)
                values
                    ('EA', 'Each', false),
                    ('KG', 'Kilogram', true)
                on conflict (uom_code) do nothing
                """);
        admin.update("delete from catalogue.uom where uom_code = 'PACK'");

        admin.update(
                """
                insert into catalogue.tax_category
                    (tax_category_id, code, name_en, owner_entity_id)
                values
                    (?, 'STD', 'Standard Tax', ?)
                on conflict (code) do nothing
                """,
                TAX_CATEGORY,
                MPCS);

        admin.update(
                """
                insert into catalogue.tax_rate
                    (tax_category_id, rate_percent, effective_from, owner_entity_id)
                values
                    ((select tax_category_id from catalogue.tax_category where code = 'STD' limit 1), 18.00, '2024-01-01', ?)
                on conflict do nothing
                """,
                MPCS);

        kernel.reset();
    }

    @AfterEach
    void cleanup() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku, catalogue.supplier, catalogue.duplicate_suspect cascade");
        admin.update("delete from catalogue.uom where uom_code = 'PACK'");
    }

    @Test
    void validateModeReportsValidationErrorsAndDuplicateSuspectsWithoutCommitting() {
        HttpHeaders headers = headers();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        Map<String, Object> validRow = new LinkedHashMap<>();
        validRow.put("rowNumber", 1);
        validRow.put("nameEn", "Highland Full Cream Milk Powder 400g");
        validRow.put("baseUomCode", "EA");
        validRow.put("taxCategoryCode", "STD");
        validRow.put("price", new BigDecimal("1050.00"));
        validRow.put("brand", "highland");
        validRow.put("barcodes", List.of("4791000000003"));

        Map<String, Object> suspectRow = new LinkedHashMap<>();
        suspectRow.put("rowNumber", 2);
        suspectRow.put("nameEn", "Highland Milk Powder 400g Pkt");
        suspectRow.put("baseUomCode", "EA");
        suspectRow.put("taxCategoryCode", "STD");
        suspectRow.put("price", new BigDecimal("1050.00"));
        suspectRow.put("brand", "highland");
        suspectRow.put("barcodes", List.of("4791000000003"));

        Map<String, Object> invalidRow = new LinkedHashMap<>();
        invalidRow.put("rowNumber", 3);
        invalidRow.put("nameEn", "   ");
        invalidRow.put("baseUomCode", "UNKNOWN");
        invalidRow.put("taxCategoryCode", "INVALID");

        Map<String, Object> request = Map.of("mode", "VALIDATE", "rows", List.of(validRow, suspectRow, invalidRow));

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/catalogue/import", HttpMethod.POST, new HttpEntity<>(request, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(body.get("rows").asInt()).isEqualTo(3);
        assertThat(body.get("rejected").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode errors = body.get("errors");
        assertThat(errors).isNotEmpty();

        JsonNode suspects = body.get("suspects");
        assertThat(suspects).isNotEmpty();

        // Verify nothing was committed to database
        JdbcTemplate admin = superuserJdbc();
        Integer skuCount = admin.queryForObject("select count(*) from catalogue.sku", Integer.class);
        Integer suspectCount = admin.queryForObject("select count(*) from catalogue.duplicate_suspect", Integer.class);
        assertThat(skuCount).isZero();
        assertThat(suspectCount).isZero();

        // Verify no audit or events committed
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void commitModeImportsSkusWithTransliterationAndFlagsDuplicateSuspects() {
        HttpHeaders headers = headers();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("rowNumber", 1);
        row1.put("nameEn", "Anchor Milk Powder 400g");
        row1.put("baseUomCode", "EA");
        row1.put("taxCategoryCode", "STD");
        row1.put("price", new BigDecimal("1080.00"));
        row1.put("brand", "anchor");
        row1.put("barcodes", List.of("4791000000003"));

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("rowNumber", 2);
        row2.put("nameEn", "Anchor Full Cream Milk Powder 400g");
        row2.put("baseUomCode", "EA");
        row2.put("taxCategoryCode", "STD");
        row2.put("price", new BigDecimal("1080.00"));
        row2.put("brand", "anchor");
        row2.put("barcodes", List.of("4791000000003"));

        Map<String, Object> request = Map.of("mode", "COMMIT", "rows", List.of(row1, row2));

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/catalogue/import", HttpMethod.POST, new HttpEntity<>(request, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.get("accepted").asInt()).isEqualTo(2);

        // Verify SKUs inserted with transliterated Sinhala and Tamil names
        JdbcTemplate admin = superuserJdbc();
        List<Map<String, Object>> skus = admin.queryForList(
                "select short_name_en, short_name_si, short_name_ta from catalogue.sku order by short_name_en");
        assertThat(skus).hasSize(2);
        for (Map<String, Object> sku : skus) {
            assertThat(sku.get("short_name_si")).asString().isNotBlank().contains("කිරි");
            assertThat(sku.get("short_name_ta")).asString().isNotBlank().contains("பால்");
        }

        // Verify barcode inserted
        List<Map<String, Object>> barcodes = admin.queryForList(
                "select barcode, symbology, status from catalogue.sku_barcode where barcode = '4791000000003'");
        assertThat(barcodes).isNotEmpty();
        assertThat(barcodes.get(0).get("symbology")).isEqualTo("EAN13");
        assertThat(barcodes.get(0).get("status")).isEqualTo("ACTIVE");

        // Verify duplicate suspect inserted in catalogue.duplicate_suspect
        List<Map<String, Object>> suspects =
                admin.queryForList("select suspect_id, score, status from catalogue.duplicate_suspect");
        assertThat(suspects).hasSize(1);
        assertThat(suspects.get(0).get("status")).isEqualTo("OPEN");

        // Verify audit trail committed
        assertThat(kernel.committedAudit()).anyMatch(a -> "SKUS_BULK_IMPORTED".equals(a.eventType()));

        // Verify domain events committed
        assertThat(kernel.committedEvents())
                .anyMatch(e -> e instanceof SkusBulkImported)
                .anyMatch(e -> e instanceof DuplicateSuspected);
    }

    @Test
    void highVolumeBulkImportPerformanceTest() {
        HttpHeaders headers = headers();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        // Generate 500 unique valid items
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rowNumber", i);
            r.put("skuCode", String.format("SKU-%05d", i));
            r.put("nameEn", "High Volume Item " + i);
            r.put("baseUomCode", "EA");
            r.put("taxCategoryCode", "STD");
            r.put("price", new BigDecimal("100.00"));
            rows.add(r);
        }

        Map<String, Object> request = Map.of("mode", "COMMIT", "rows", rows);

        long start = System.currentTimeMillis();
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/catalogue/import", HttpMethod.POST, new HttpEntity<>(request, headers), JsonNode.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("accepted").asInt()).isEqualTo(500);

        // Verify batch performance: 500 items inserted quickly
        assertThat(elapsed).isLessThan(8000);

        JdbcTemplate admin = superuserJdbc();
        Integer count = admin.queryForObject("select count(*) from catalogue.sku", Integer.class);
        assertThat(count).isEqualTo(500);
    }

    @Test
    void duplicateSuspectsQueryAndResolveFlow() {
        HttpHeaders headers = headers();

        // 1. Commit 2 duplicate items to generate an open suspect
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> row1 = Map.of(
                "rowNumber", 1,
                "nameEn", "Maliban Marie Biscuit 300g",
                "baseUomCode", "EA",
                "taxCategoryCode", "STD",
                "brand", "maliban",
                "barcodes", List.of("4791000000003"));
        Map<String, Object> row2 = Map.of(
                "rowNumber", 2,
                "nameEn", "Maliban Marie Biscuits 300g Pkt",
                "baseUomCode", "EA",
                "taxCategoryCode", "STD",
                "brand", "maliban",
                "barcodes", List.of("4791000000003"));

        http.exchange(
                "/v1/catalogue/import",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("mode", "COMMIT", "rows", List.of(row1, row2)), headers),
                JsonNode.class);

        // 2. Query open suspects via GET /v1/catalogue/steward/suspects
        ResponseEntity<JsonNode> queryRes = http.exchange(
                "/v1/catalogue/steward/suspects?status=OPEN",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);

        assertThat(queryRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode suspects = queryRes.getBody();
        assertThat(suspects).isNotNull();
        assertThat(suspects.isArray()).isTrue();
        assertThat(suspects.size()).isGreaterThanOrEqualTo(1);

        String suspectId = suspects.get(0).get("suspectId").asText();

        // 3. Resolve the suspect as MERGED via POST /v1/catalogue/steward/suspects/{suspectId}/resolve
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> resolveReq = Map.of("decision", "MERGED");

        ResponseEntity<Void> resolveRes = http.exchange(
                "/v1/catalogue/steward/suspects/" + suspectId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(resolveReq, headers),
                Void.class);

        assertThat(resolveRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify status in database
        JdbcTemplate admin = superuserJdbc();
        String status = admin.queryForObject(
                "select status from catalogue.duplicate_suspect where suspect_id = ?",
                String.class,
                UUID.fromString(suspectId));
        assertThat(status).isEqualTo("MERGED");

        // Verify audit and domain events
        assertThat(kernel.committedAudit()).anyMatch(a -> "SUSPECT_RESOLVED".equals(a.eventType()));
        assertThat(kernel.committedEvents()).anyMatch(e -> e instanceof DuplicateSuspectResolved);
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(USER, MPCS));
        headers.set("X-Scope-Entity", MPCS.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
