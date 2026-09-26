package lk.coopfed.knoweb.kernel.internal.document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;

/**
 * SHA-256 over the canonical form of a document (doc 18: "content_hash: SHA-256 over the
 * canonical header and lines; verified on sync"). Canonical means: the fields below, in this
 * order, one per line as {@code name=value}, a line as {@code line:} followed by its fields,
 * lines in line order; a null is the empty string, a decimal rounded to the scale of its
 * column (money 2, unit price and cost 4, quantity 3, tax rate 3; doc 18 part F) and then its
 * plain string with trailing zeros stripped, so that 10.0 and 10.00 hash alike and a value the
 * database rounds on the way in (33.33333 stored as 33.3333) hashes as what is stored. No
 * JSON library is involved, so the till (Kotlin) can compute the same hash from the same rule
 * without sharing a serializer.
 *
 * <p>The hash covers what the issuer fixed: identity, parties, place, number, timestamps,
 * totals, origin, and every fact on the lines. It does not cover status (which changes) or
 * notes.
 */
final class ContentHash {

    /** The column scales of kernel.document and kernel.document_line (V0050). */
    private static final int MONEY = 2;

    private static final int UNIT_PRICE = 4;
    private static final int QUANTITY = 3;
    private static final int TAX_RATE = 3;

    private ContentHash() {}

    static String of(DocumentRecord header, List<DocumentLineRecord> lines) {
        StringBuilder canon = new StringBuilder(512);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("document_id", header.id());
        fields.put("doc_type_code", header.docTypeCode());
        fields.put("series_id", header.seriesId());
        fields.put("doc_number", header.docNumber());
        fields.put("doc_number_display", header.docNumberDisplay());
        fields.put("owner_entity_id", header.ownerEntityId());
        fields.put("counterparty_entity_id", header.counterpartyEntityId());
        fields.put("location_id", header.locationId());
        fields.put("till_position_id", header.tillPositionId());
        fields.put("device_id", header.deviceId());
        fields.put("issued_at", header.issuedAt());
        fields.put("business_date", header.businessDate());
        fields.put("operator_user_id", header.operatorUserId());
        fields.put("currency", header.currency());
        fields.put("net_amount", scaled(header.netAmount(), MONEY));
        fields.put("tax_amount", scaled(header.taxAmount(), MONEY));
        fields.put("gross_amount", scaled(header.grossAmount(), MONEY));
        fields.put("reference_document_id", header.referenceDocumentId());
        fields.put("origin", header.origin());
        fields.put("device_seq", header.deviceSeq());
        append(canon, fields);

        lines.stream()
                .sorted(Comparator.comparingInt(DocumentLineRecord::lineNo))
                .forEach(line -> {
                    canon.append("line:\n");
                    Map<String, Object> lineFields = new LinkedHashMap<>();
                    lineFields.put("line_no", line.lineNo());
                    lineFields.put("sku_id", line.skuId());
                    lineFields.put("batch_id", line.batchId());
                    lineFields.put("uom_code", line.uomCode());
                    lineFields.put("qty", scaled(line.qty(), QUANTITY));
                    lineFields.put("unit_price", scaled(line.unitPrice(), UNIT_PRICE));
                    lineFields.put("mrp_applied", scaled(line.mrpApplied(), UNIT_PRICE));
                    lineFields.put("control_price_applied", scaled(line.controlPriceApplied(), UNIT_PRICE));
                    lineFields.put("cap_reason", line.capReason());
                    lineFields.put("discount_rule_id", line.discountRuleId());
                    lineFields.put("discount_amount", scaled(line.discountAmount(), MONEY));
                    lineFields.put("tax_rate_percent", scaled(line.taxRatePercent(), TAX_RATE));
                    lineFields.put("tax_amount", scaled(line.taxAmount(), MONEY));
                    lineFields.put("line_total", scaled(line.lineTotal(), MONEY));
                    lineFields.put("unit_cost_at_issue", scaled(line.unitCostAtIssue(), UNIT_PRICE));
                    lineFields.put("loss_category", line.lossCategory());
                    lineFields.put("reference_line_id", line.referenceLineId());
                    append(canon, lineFields);
                });

        return sha256(canon.toString());
    }

    private static void append(StringBuilder canon, Map<String, Object> fields) {
        fields.forEach((name, value) ->
                canon.append(name).append('=').append(canonical(value)).append('\n'));
    }

    /** The value as PostgreSQL stores it: rounded half away from zero to the column's scale. */
    private static BigDecimal scaled(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static String canonical(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            BigDecimal stripped = decimal.stripTrailingZeros();
            return stripped.signum() == 0 ? "0" : stripped.toPlainString();
        }
        return Objects.toString(value);
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
