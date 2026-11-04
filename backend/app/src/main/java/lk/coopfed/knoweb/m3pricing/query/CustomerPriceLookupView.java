package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * CustomerPriceLookupView (M3-08): Sanitized public presentation of a product's verified retail price
 * at a shop location. Strictly omits internal cost, margin, price list ID, batch ID, policy algorithm,
 * or restricted owner data.
 */
public record CustomerPriceLookupView(
        UUID skuId,
        String skuCode,
        String productName,
        String uom,
        BigDecimal publicPrice,
        String currency,
        boolean isAvailable,
        BigDecimal controlPrice,
        boolean isPriceControlled,
        UUID locationId,
        String locationName,
        LocalDate effectiveDate) {}
