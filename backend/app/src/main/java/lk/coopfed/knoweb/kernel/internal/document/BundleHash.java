package lk.coopfed.knoweb.kernel.internal.document;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;

/**
 * The content hash of a document bundle a till uploaded (doc 32 section 3.3 step 4, "verify
 * content_hash for bundles"; doc 18: "SHA-256 over the canonical header and lines; verified on
 * sync"). The bundle carries the header and the lines with doc 18's column names; this reads
 * the fields {@link ContentHash} covers and hashes them by the same rule the issuance protocol
 * uses, so a document issued offline and one issued at central hash alike.
 *
 * <pre>
 *   payload.document   document_id, doc_type_code, series_id, doc_number, doc_number_display,
 *                      owner_entity_id, counterparty_entity_id, location_id, till_position_id,
 *                      device_id, issued_at, business_date, operator_user_id, currency,
 *                      net_amount, tax_amount, gross_amount, reference_document_id, origin,
 *                      device_seq
 *   payload.lines[]    line_no, sku_id, batch_id, uom_code, qty, unit_price, mrp_applied,
 *                      control_price_applied, cap_reason, discount_rule_id, discount_amount,
 *                      tax_rate_percent, tax_amount, line_total, unit_cost_at_issue,
 *                      loss_category, reference_line_id
 * </pre>
 */
public final class BundleHash {

    private BundleHash() {}

    /**
     * The hash of the bundle's document and lines.
     *
     * @throws IllegalArgumentException when the document or a line cannot be read (a missing
     *                                  document, a value of the wrong kind): the event is
     *                                  malformed, not merely mismatched
     */
    public static String of(JsonNode document, JsonNode lines) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("payload.document is missing");
        }
        if (lines != null && !lines.isNull() && !lines.isArray()) {
            throw new IllegalArgumentException("payload.lines is not a list");
        }
        UUID documentId = uuid(document, "document_id");
        String origin = text(document, "origin");
        DocumentRecord header = new DocumentRecord(
                documentId,
                text(document, "doc_type_code"),
                uuid(document, "series_id"),
                longValue(document, "doc_number"),
                text(document, "doc_number_display"),
                uuid(document, "owner_entity_id"),
                uuid(document, "counterparty_entity_id"),
                uuid(document, "location_id"),
                uuid(document, "till_position_id"),
                uuid(document, "device_id"),
                null,
                instant(document, "issued_at"),
                null,
                date(document, "business_date"),
                uuid(document, "operator_user_id"),
                text(document, "currency"),
                decimal(document, "net_amount"),
                decimal(document, "tax_amount"),
                decimal(document, "gross_amount"),
                uuid(document, "reference_document_id"),
                null,
                origin == null ? null : DocumentOrigin.valueOf(origin),
                longValue(document, "device_seq"),
                null);
        List<DocumentLineRecord> records = new ArrayList<>();
        if (lines != null && lines.isArray()) {
            for (JsonNode line : lines) {
                if (!line.isObject()) {
                    throw new IllegalArgumentException("a line is not an object");
                }
                Long lineNo = longValue(line, "line_no");
                if (lineNo == null) {
                    throw new IllegalArgumentException("a line has no line_no");
                }
                records.add(new DocumentLineRecord(
                        null,
                        documentId,
                        lineNo.intValue(),
                        uuid(line, "sku_id"),
                        uuid(line, "batch_id"),
                        text(line, "uom_code"),
                        decimal(line, "qty"),
                        decimal(line, "unit_price"),
                        decimal(line, "mrp_applied"),
                        decimal(line, "control_price_applied"),
                        text(line, "cap_reason"),
                        uuid(line, "discount_rule_id"),
                        decimal(line, "discount_amount"),
                        decimal(line, "tax_rate_percent"),
                        decimal(line, "tax_amount"),
                        decimal(line, "line_total"),
                        decimal(line, "unit_cost_at_issue"),
                        text(line, "loss_category"),
                        uuid(line, "reference_line_id")));
            }
        }
        return ContentHash.of(header, records);
    }

    private static JsonNode field(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = field(node, name);
        return value == null ? null : value.asText();
    }

    private static UUID uuid(JsonNode node, String name) {
        String value = text(node, name);
        return value == null ? null : UUID.fromString(value);
    }

    private static Long longValue(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (value == null) {
            return null;
        }
        if (value.canConvertToExactIntegral() || value.isIntegralNumber()) {
            return value.asLong();
        }
        return Long.parseLong(value.asText());
    }

    /**
     * A decimal from its text, never through a double: 12.35 stays 12.35 whichever way the JSON
     * reader held it.
     */
    private static BigDecimal decimal(JsonNode node, String name) {
        JsonNode value = field(node, name);
        return value == null ? null : new BigDecimal(value.asText());
    }

    private static Instant instant(JsonNode node, String name) {
        String value = text(node, name);
        return value == null ? null : Instant.parse(value);
    }

    private static LocalDate date(JsonNode node, String name) {
        String value = text(node, name);
        return value == null ? null : LocalDate.parse(value);
    }
}
