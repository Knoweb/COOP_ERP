package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Bulk import price list lines from a sheet or line items into a draft list (CAT-13).
 */
public record ImportPriceListLines(UUID priceListId, String sheet, List<ImportLineRow> lines, boolean replace) {

    public ImportPriceListLines(UUID priceListId, String sheet, List<ImportLineRow> lines) {
        this(priceListId, sheet, lines, true);
    }

    public record ImportLineRow(String sku, UUID skuId, String uomCode, BigDecimal tierFromQty, BigDecimal price) {}
}
