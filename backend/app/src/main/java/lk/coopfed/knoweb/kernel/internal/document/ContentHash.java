package lk.coopfed.knoweb.kernel.internal.document;

import java.math.BigDecimal;
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
 * lines in line order; a null is the empty string, a decimal its plain string with trailing
 * zeros stripped, so that 10.0 and 10.00 hash alike. No JSON library is involved, so the
 * till (Kotlin) can compute the same hash from the same rule without sharing a serializer.
 *
 * <p>The hash covers what the issuer fixed: identity, parties, place, number, timestamps,
 * totals, origin, and every fact on the lines. It does not cover status (which changes) or
 * notes.
 */
final class ContentHash {

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
        fields.put("net_amount", header.netAmount());
        fields.put("tax_amount", header.taxAmount());
        fields.put("gross_amount", header.grossAmount());
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
                    lineFields.put("qty", line.qty());
                    lineFields.put("unit_price", line.unitPrice());
                    lineFields.put("mrp_applied", line.mrpApplied());
                    lineFields.put("control_price_applied", line.controlPriceApplied());
                    lineFields.put("cap_reason", line.capReason());
                    lineFields.put("discount_rule_id", line.discountRuleId());
                    lineFields.put("discount_amount", line.discountAmount());
                    lineFields.put("tax_rate_percent", line.taxRatePercent());
                    lineFields.put("tax_amount", line.taxAmount());
                    lineFields.put("line_total", line.lineTotal());
                    lineFields.put("unit_cost_at_issue", line.unitCostAtIssue());
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
