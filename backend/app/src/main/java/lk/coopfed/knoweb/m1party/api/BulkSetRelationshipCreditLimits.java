package lk.coopfed.knoweb.m1party.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Bulk command to set trading relationship credit limits from a sheet or rows (CAT-14).
 */
public record BulkSetRelationshipCreditLimits(String sheet, List<RelationshipLimitRow> limits, String defaultReason) {

    public BulkSetRelationshipCreditLimits {
        limits = limits == null ? List.of() : List.copyOf(limits);
    }

    public record RelationshipLimitRow(String buyerCode, UUID buyerEntityId, BigDecimal creditLimit, String reason) {}
}
