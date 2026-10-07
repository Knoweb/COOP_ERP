package lk.coopfed.knoweb.kernel.internal.sync;

import static lk.coopfed.knoweb.kernel.api.DevicePayloadCheck.firstNot;
import static lk.coopfed.knoweb.kernel.api.DevicePayloadCheck.present;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.DevicePayloadCheck;

/**
 * The generic shape of a document bundle (doc 32 section 3.1), checked by the gateway before the
 * event reaches the outbox (wave 2, decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md (1)). Beside
 * {@link lk.coopfed.knoweb.kernel.internal.document.BundleHash}, which reads the same fields for
 * the content hash. Why at the gateway: M5 ({@code m5.sales}), M7 ({@code m7.tenders}) and M8
 * ({@code m8.shop-sales}) each consume the same outbox event as M6, so a bundle one of them cannot
 * read must reach none of them, or the stock moves for a sale the receipt list does not hold.
 *
 * <pre>
 *   document.document_id    a UUID
 *   document.issued_at      an instant
 *   money, quantity, price  when present (not null), a decimal written as text ("1450.00")
 *   a UUID field            when present, a UUID
 *   lines                   a list; each line an object with an integer line_no of 1 or more,
 *                           no two alike
 *   tenders                 when present, a list; each an object with an integer seq of 1 or
 *                           more, no two alike, a kind that is not blank and a decimal amount
 * </pre>
 *
 * Exactly the shape the till writes (till/core Facts.kt: no line_id, no batch_id, tax "0.00") and
 * no business rule: a total that does not add up, an unknown session, a duplicate number are the
 * applying module's flags (AGENTS.md: applied and flagged, never refused).
 */
final class BundleShape {

    private static final List<String> DOCUMENT_UUIDS = List.of(
            "series_id",
            "owner_entity_id",
            "counterparty_entity_id",
            "location_id",
            "till_position_id",
            "device_id",
            "operator_user_id",
            "reference_document_id");

    private static final List<String> DOCUMENT_DECIMALS = List.of("net_amount", "tax_amount", "gross_amount");

    private static final List<String> LINE_UUIDS =
            List.of("sku_id", "batch_id", "discount_rule_id", "reference_line_id");

    private static final List<String> LINE_DECIMALS = List.of(
            "qty",
            "unit_price",
            "mrp_applied",
            "control_price_applied",
            "discount_amount",
            "tax_rate_percent",
            "tax_amount",
            "line_total",
            "unit_cost_at_issue");

    /** The payload's own UUID fields beside the document (the receipt's session). */
    private static final List<String> PAYLOAD_UUIDS = List.of("session_id");

    private static final List<String> TENDER_UUIDS = List.of("customer_account_id");

    private BundleShape() {}

    /** What is wrong with the bundle's payload, or null when it has the shape. */
    static String problemWith(JsonNode payload) {
        JsonNode document = payload.get("document");
        if (document == null || !document.isObject()) {
            return "payload.document is missing or not an object";
        }
        if (!DevicePayloadCheck.isUuid(present(document, "document_id"))) {
            return "document.document_id is missing or not a UUID";
        }
        if (!DevicePayloadCheck.isInstant(present(document, "issued_at"))) {
            return "document.issued_at is missing or not an instant";
        }
        String wrong = firstNot(document, DOCUMENT_UUIDS, DevicePayloadCheck::isUuid, "a UUID");
        if (wrong == null) {
            wrong = firstNot(
                    document, DOCUMENT_DECIMALS, DevicePayloadCheck::isDecimalText, "a decimal written as text");
        }
        if (wrong != null) {
            return "document." + wrong;
        }
        wrong = firstNot(payload, PAYLOAD_UUIDS, DevicePayloadCheck::isUuid, "a UUID");
        if (wrong != null) {
            return "payload." + wrong;
        }
        String lines = lines(payload.get("lines"));
        return lines != null ? lines : tenders(present(payload, "tenders"));
    }

    private static String lines(JsonNode lines) {
        if (lines == null || !lines.isArray()) {
            return "payload.lines is missing or not a list";
        }
        Set<Integer> seen = new HashSet<>();
        for (JsonNode line : lines) {
            if (!line.isObject()) {
                return "a line is not an object";
            }
            JsonNode lineNo = line.get("line_no");
            if (!positiveInteger(lineNo)) {
                return "a line has no line_no that is an integer of 1 or more";
            }
            if (!seen.add(lineNo.asInt())) {
                return "line_no " + lineNo.asInt() + " is on two lines";
            }
            String wrong = firstNot(line, LINE_UUIDS, DevicePayloadCheck::isUuid, "a UUID");
            if (wrong == null) {
                wrong = firstNot(line, LINE_DECIMALS, DevicePayloadCheck::isDecimalText, "a decimal written as text");
            }
            if (wrong != null) {
                return "line " + lineNo.asInt() + ": " + wrong;
            }
        }
        return null;
    }

    private static String tenders(JsonNode tenders) {
        if (tenders == null) {
            return null;
        }
        if (!tenders.isArray()) {
            return "payload.tenders is not a list";
        }
        Set<Integer> seen = new HashSet<>();
        for (JsonNode tender : tenders) {
            if (!tender.isObject()) {
                return "a tender is not an object";
            }
            JsonNode seq = tender.get("seq");
            if (!positiveInteger(seq)) {
                return "a tender has no seq that is an integer of 1 or more";
            }
            if (!seen.add(seq.asInt())) {
                return "tender seq " + seq.asInt() + " is on two tenders";
            }
            JsonNode kind = present(tender, "kind");
            if (kind == null || !kind.isTextual() || kind.asText().isBlank()) {
                return "tender " + seq.asInt() + " has no kind";
            }
            if (!DevicePayloadCheck.isDecimalText(present(tender, "amount"))) {
                return "tender " + seq.asInt() + ": amount is missing or not a decimal written as text";
            }
            String wrong = firstNot(tender, TENDER_UUIDS, DevicePayloadCheck::isUuid, "a UUID");
            if (wrong != null) {
                return "tender " + seq.asInt() + ": " + wrong;
            }
        }
        return null;
    }

    /** A JSON integer (not text) from 1 to the largest int: what the till writes, and what M6 stores. */
    private static boolean positiveInteger(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 1;
    }
}
