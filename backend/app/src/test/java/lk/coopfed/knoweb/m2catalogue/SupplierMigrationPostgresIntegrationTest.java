package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

@DisplayName("Supplier Master Data Migration HTTP and RLS Integration Test")
class SupplierMigrationPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS_A = UUID.fromString("0190e620-0000-7000-8000-000000000002");
    private static final UUID MPCS_B = UUID.fromString("0190e620-0000-7000-8000-000000000003");
    private static final UUID USER_A = UUID.fromString("0190e620-0000-7000-8000-000000000010");
    private static final UUID USER_B = UUID.fromString("0190e620-0000-7000-8000-000000000011");

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void cleanSuppliers() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.supplier cascade");
        kernel.reset();
    }

    @AfterEach
    void tearDown() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.supplier cascade");
    }

    @Test
    @DisplayName("VALIDATE mode dry run guarantees 0 database writes, 0 audit records, and 0 domain events")
    void validateModePerformsDryRunWithZeroWritesAuditsOrEvents() {
        HttpHeaders headers = headers(USER_A, MPCS_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        String csv =
                """
                Name,SupplierCode
                Ceylon Tea Board,SUP-CTB
                Lanka Sugar Ltd,SUP-LSL
                """;

        Map<String, Object> body = Map.of("mode", "VALIDATE", "csvContent", csv);

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/catalogue/suppliers/import", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = response.getBody();
        assertThat(json).isNotNull();
        assertThat(json.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(json.get("rows").asInt()).isEqualTo(2);
        assertThat(json.get("created").asInt()).isEqualTo(2);
        assertThat(json.get("skipped").asInt()).isEqualTo(0);
        assertThat(json.get("rejected").asInt()).isEqualTo(0);
        assertThat(json.get("errors")).isEmpty();

        // Database must remain completely untouched
        Integer count = superuserJdbc().queryForObject("SELECT count(*) FROM catalogue.supplier", Integer.class);
        assertThat(count).isZero();

        // Kernel audit and events must be empty
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    @DisplayName("COMMIT mode imports suppliers, persists supplier_code, and commits audit and domain events")
    void commitModeImportsSuppliersAndEmitsAuditAndEvents() {
        HttpHeaders headers = headers(USER_A, MPCS_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        String csv =
                """
                Name,SupplierCode
                Ceylon Tea Board,SUP-CTB
                Lanka Sugar Ltd,SUP-LSL
                """;

        Map<String, Object> body = Map.of("mode", "COMMIT", "csvContent", csv);

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/catalogue/suppliers/import", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = response.getBody();
        assertThat(json).isNotNull();
        assertThat(json.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(json.get("created").asInt()).isEqualTo(2);
        assertThat(json.get("skipped").asInt()).isEqualTo(0);
        assertThat(json.get("rejected").asInt()).isEqualTo(0);

        // Verify database persistence
        List<Map<String, Object>> rows = superuserJdbc()
                .queryForList(
                        "SELECT name, supplier_code, owner_entity_id, status FROM catalogue.supplier WHERE owner_entity_id = ? ORDER BY name",
                        MPCS_A);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("name")).isEqualTo("Ceylon Tea Board");
        assertThat(rows.get(0).get("supplier_code")).isEqualTo("SUP-CTB");
        assertThat(rows.get(1).get("name")).isEqualTo("Lanka Sugar Ltd");
        assertThat(rows.get(1).get("supplier_code")).isEqualTo("SUP-LSL");

        // Verify GET /v1/catalogue/suppliers returns persisted supplier codes
        ResponseEntity<JsonNode> listResponse =
                http.exchange("/v1/catalogue/suppliers", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode listJson = listResponse.getBody();
        assertThat(listJson).isNotNull().hasSize(2);
        assertThat(listJson.get(0).get("name").asText()).isEqualTo("Ceylon Tea Board");
        assertThat(listJson.get(0).get("supplierCode").asText()).isEqualTo("SUP-CTB");
        assertThat(listJson.get(1).get("name").asText()).isEqualTo("Lanka Sugar Ltd");
        assertThat(listJson.get(1).get("supplierCode").asText()).isEqualTo("SUP-LSL");

        // Verify audit event
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("SUPPLIERS_BULK_IMPORTED");

        // Verify domain event
        assertThat(kernel.committedEvents())
                .extracting(event -> event.getClass().getSimpleName())
                .contains("SuppliersBulkImported");
    }

    @Test
    @DisplayName(
            "Re-importing identical records is idempotent, skipping existing suppliers with 0 duplicates and 0 extra events")
    void idempotentReImportSkipsIdenticalSuppliers() {
        HttpHeaders headers = headers(USER_A, MPCS_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        String csv =
                """
                Name,SupplierCode
                Ceylon Tea Board,SUP-CTB
                Lanka Sugar Ltd,SUP-LSL
                """;

        Map<String, Object> body = Map.of("mode", "COMMIT", "csvContent", csv);

        // Initial import
        http.exchange(
                "/v1/catalogue/suppliers/import", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        kernel.reset();

        // Repeated import with fresh idempotency key
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> secondResponse = http.exchange(
                "/v1/catalogue/suppliers/import", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = secondResponse.getBody();
        assertThat(json).isNotNull();
        assertThat(json.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(json.get("created").asInt()).isEqualTo(0);
        assertThat(json.get("skipped").asInt()).isEqualTo(2);
        assertThat(json.get("rejected").asInt()).isEqualTo(0);

        // Row count remains 2
        Integer count = superuserJdbc()
                .queryForObject(
                        "SELECT count(*) FROM catalogue.supplier WHERE owner_entity_id = ?", Integer.class, MPCS_A);
        assertThat(count).isEqualTo(2);

        // Audit records that 0 were created and 2 were skipped
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("SUPPLIERS_BULK_IMPORTED");
        // No duplicate domain events on fully skipped batch
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    @DisplayName("Row-level security isolates suppliers between entities, allowing same code under different tenants")
    void rowLevelSecurityIsolatesEntities() {
        // MPCS A imports SUP-001
        HttpHeaders headersA = headers(USER_A, MPCS_A);
        headersA.set("Idempotency-Key", UUID.randomUUID().toString());
        http.exchange(
                "/v1/catalogue/suppliers/import",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("mode", "COMMIT", "csvContent", "Name,SupplierCode\nLocal Farmer A,SUP-001"), headersA),
                JsonNode.class);

        // MPCS B imports SUP-001 with distinct name
        HttpHeaders headersB = headers(USER_B, MPCS_B);
        headersB.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> responseB = http.exchange(
                "/v1/catalogue/suppliers/import",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("mode", "COMMIT", "csvContent", "Name,SupplierCode\nLocal Farmer B,SUP-001"), headersB),
                JsonNode.class);

        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseB.getBody().get("created").asInt()).isEqualTo(1);

        // RLS ensures tenant A and tenant B have 1 supplier each
        Integer countA = superuserJdbc()
                .queryForObject(
                        "SELECT count(*) FROM catalogue.supplier WHERE owner_entity_id = ?", Integer.class, MPCS_A);
        Integer countB = superuserJdbc()
                .queryForObject(
                        "SELECT count(*) FROM catalogue.supplier WHERE owner_entity_id = ?", Integer.class, MPCS_B);
        assertThat(countA).isEqualTo(1);
        assertThat(countB).isEqualTo(1);
    }

    @Test
    @DisplayName("Rejects conflicting supplier records with actionable row-level diagnostics")
    void rejectsConflictingSupplierRecords() {
        // Existing supplier: Ceylon Tea Board with SUP-CTB
        HttpHeaders headers = headers(USER_A, MPCS_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        http.exchange(
                "/v1/catalogue/suppliers/import",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("mode", "COMMIT", "csvContent", "Name,SupplierCode\nCeylon Tea Board,SUP-CTB"), headers),
                JsonNode.class);

        // Conflicting import: SUP-CTB assigned to different name
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        String conflictCsv =
                """
                Name,SupplierCode
                Highland Milk Products,SUP-CTB
                """;

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/catalogue/suppliers/import",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("mode", "VALIDATE", "csvContent", conflictCsv), headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = response.getBody();
        assertThat(json).isNotNull();
        assertThat(json.get("status").asText()).isEqualTo("REJECTED");
        assertThat(json.get("rejected").asInt()).isEqualTo(1);
        JsonNode error = json.get("errors").get(0);
        assertThat(error.get("code").asText()).isEqualTo("m2.supplier.code_conflict");
        assertThat(error.get("field").asText()).isEqualTo("supplierCode");
        assertThat(error.get("rejectedValue").asText()).isEqualTo("SUP-CTB");
    }

    private static HttpHeaders headers(UUID userId, UUID entityId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(userId, entityId));
        headers.set("X-Scope-Entity", entityId.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
