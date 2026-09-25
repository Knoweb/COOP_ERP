package lk.coopfed.knoweb.kernel.internal.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import org.junit.jupiter.api.Test;

/**
 * A till's bundle hashes as the issuance protocol hashes the same document (doc 18: "SHA-256 over
 * the canonical header and lines; verified on sync"), whatever way the JSON wrote its numbers.
 */
class BundleHashTest {

    private static final UUID DOCUMENT = UUID.fromString("0190a900-0000-7000-8000-000000000001");
    private static final UUID ENTITY = UUID.fromString("0190a900-0000-7000-8000-000000000002");
    private static final UUID SKU = UUID.fromString("0190a900-0000-7000-8000-000000000003");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aBundleHashesLikeTheDocumentItCarries() throws Exception {
        DocumentRecord header = new DocumentRecord(
                DOCUMENT,
                "RCT",
                null,
                12L,
                "M001-S01-T1-RCT-0000012",
                ENTITY,
                null,
                null,
                null,
                null,
                "ISSUED",
                Instant.parse("2026-09-25T04:30:00Z"),
                null,
                LocalDate.parse("2026-09-25"),
                null,
                "LKR",
                new BigDecimal("250.00"),
                new BigDecimal("0.00"),
                new BigDecimal("250.00"),
                null,
                null,
                DocumentOrigin.OFFLINE,
                12L,
                "a note the hash does not cover");
        DocumentLineRecord line = new DocumentLineRecord(
                null,
                DOCUMENT,
                1,
                SKU,
                null,
                "EA",
                new BigDecimal("2.000"),
                new BigDecimal("125.0000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("250.00"),
                null,
                null,
                null);

        JsonNode document = json.readTree(
                """
                {"document_id":"%s","doc_type_code":"RCT","doc_number":12,"doc_number_display":"M001-S01-T1-RCT-0000012",
                 "owner_entity_id":"%s","issued_at":"2026-09-25T04:30:00.000Z","business_date":"2026-09-25",
                 "currency":"LKR","net_amount":250,"tax_amount":0.0,"gross_amount":"250.00","origin":"OFFLINE",
                 "device_seq":12,"status":"ISSUED"}
                """
                        .formatted(DOCUMENT, ENTITY));
        JsonNode lines = json.readTree(
                """
                [{"line_no":1,"sku_id":"%s","uom_code":"EA","qty":2,"unit_price":125.0,"line_total":250.00}]
                """
                        .formatted(SKU));

        assertThat(BundleHash.of(document, lines)).isEqualTo(ContentHash.of(header, List.of(line)));
    }

    @Test
    void aChangedLineChangesTheHash() throws Exception {
        JsonNode document = json.readTree("{\"document_id\":\"" + DOCUMENT + "\",\"origin\":\"OFFLINE\"}");
        String one = BundleHash.of(document, json.readTree("[{\"line_no\":1,\"qty\":2}]"));
        String other = BundleHash.of(document, json.readTree("[{\"line_no\":1,\"qty\":3}]"));

        assertThat(one).isNotEqualTo(other).hasSize(64);
    }

    @Test
    void aBundleWithoutADocumentOrWithAnUnreadableFieldIsMalformed() throws Exception {
        assertThatThrownBy(() -> BundleHash.of(null, null)).isInstanceOf(IllegalArgumentException.class);
        JsonNode badTime = json.readTree("{\"document_id\":\"" + DOCUMENT + "\",\"issued_at\":\"yesterday\"}");
        assertThatThrownBy(() -> BundleHash.of(badTime, null)).isInstanceOf(RuntimeException.class);
        JsonNode badId = json.readTree("{\"document_id\":\"not-a-uuid\"}");
        assertThatThrownBy(() -> BundleHash.of(badId, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
