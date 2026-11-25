package lk.coopfed.knoweb.m1party.internal.bulk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m1party.api.BulkSetRelationshipCreditLimits.RelationshipLimitRow;

/**
 * Parses a CSV sheet of trading relationship credit limits (CAT-14).
 */
public final class BulkRelationshipCreditLimitsCsvParser {

    private BulkRelationshipCreditLimitsCsvParser() {}

    public static List<RelationshipLimitRow> parse(String csvText) {
        if (csvText == null || csvText.isBlank()) {
            return List.of();
        }
        CsvTable table = CsvTable.parse(csvText);
        if (table.rows().isEmpty()) {
            return List.of();
        }
        List<RelationshipLimitRow> list = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            String buyerCode = valueOf(row, "buyer_code", "buyer", "buyer_entity_code", "entity_code", "code");
            String buyerIdStr = valueOf(row, "buyer_id", "buyer_entity_id", "entity_id", "id");
            String limitStr = valueOf(row, "credit_limit", "limit", "amount", "new_limit");
            String reason = valueOf(row, "reason", "notes", "description");

            UUID buyerId = null;
            if (!buyerIdStr.isEmpty()) {
                try {
                    buyerId = UUID.fromString(buyerIdStr);
                } catch (IllegalArgumentException ignored) {
                    // Handled during validation
                }
            }

            BigDecimal limit = null;
            if (!limitStr.isEmpty()) {
                try {
                    limit = new BigDecimal(limitStr.replace(",", "").trim());
                } catch (NumberFormatException ignored) {
                    // Handled during validation
                }
            }

            if (!buyerCode.isEmpty() || buyerId != null || limit != null) {
                list.add(new RelationshipLimitRow(
                        buyerCode.isEmpty() ? null : buyerCode, buyerId, limit, reason.isEmpty() ? null : reason));
            }
        }
        return List.copyOf(list);
    }

    private static String valueOf(CsvTable.Row row, String... aliases) {
        for (String alias : aliases) {
            String val = row.get(alias);
            if (!val.isEmpty()) {
                return val;
            }
        }
        return "";
    }
}
