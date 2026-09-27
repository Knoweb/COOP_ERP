package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
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
import org.springframework.jdbc.core.JdbcTemplate;

/** attachImage and retireImage through the HTTP contract of the slice (22A section 5; M2-06). */
@Import(MemoryObjectStore.class)
class ImageHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e690-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e690-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e690-0000-7000-8000-000000000100");
    private static final UUID SKU = UUID.fromString("0190e690-0000-7000-8000-000000000200");
    private static final String HASH = "a".repeat(64);

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void arrange() {
        JdbcTemplate admin = superuserJdbc();
        clean();
        admin.update("insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false)"
                + " on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M2IMGHTTP', 'M2 image HTTP tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                MPCS);
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, base_uom_code,"
                        + " tax_category_id) values (?, 'IMG-HTTP', ?, 'LOCAL', 'Red rice', 'EA', ?)",
                SKU,
                MPCS,
                TAX_CATEGORY);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku cascade");
        admin.execute("delete from kernel.object_upload where owner_module = 'm2catalogue'");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void attachAndRetireFollowTheGeneratedContract() {
        HttpHeaders headers = headers();

        ResponseEntity<JsonNode> attached = http.exchange(
                "/v1/catalogue/skus/" + SKU + "/images",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("contentType", "image/png", "contentLength", 2048, "sha256Hex", HASH), headers),
                JsonNode.class);

        assertThat(attached.getStatusCode())
                .as(String.valueOf(attached.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String imageId = attached.getBody().get("imageId").asText();
        assertThat(attached.getBody().get("uploadUrl").asText())
                .contains("objects/m2catalogue/" + MPCS + "/" + imageId);
        assertThat(attached.getBody().get("expiresAt").asText()).isNotBlank();
        assertThat(attached.getHeaders().getLocation()).hasToString("/v1/catalogue/skus/" + SKU + "/images/" + imageId);
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("IMAGE_ATTACHED");

        // The schema holds the hash to 64 hex digits before any handler runs.
        HttpHeaders again = headers();
        ResponseEntity<JsonNode> badHash = http.exchange(
                "/v1/catalogue/skus/" + SKU + "/images",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("contentType", "image/png", "sha256Hex", "xyz"), again),
                JsonNode.class);
        assertThat(badHash.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Void> retired = http.exchange(
                "/v1/catalogue/skus/" + SKU + "/images/" + imageId,
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                Void.class);
        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("IMAGE_RETIRED");

        ResponseEntity<JsonNode> missing = http.exchange(
                "/v1/catalogue/skus/" + SKU + "/images/" + UUID.randomUUID(),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code").asText()).isEqualTo("m2.image.not_found");
    }

    @Test
    void anAttachWithoutIdempotencyKeyIsRejected() {
        HttpHeaders headers = headers();
        headers.remove("Idempotency-Key");

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/catalogue/skus/" + SKU + "/images",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("contentType", "image/png", "sha256Hex", HASH), headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(USER, MPCS));
        headers.set("X-Scope-Entity", MPCS.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
