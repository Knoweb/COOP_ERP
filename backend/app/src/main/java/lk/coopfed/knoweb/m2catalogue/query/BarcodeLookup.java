package lk.coopfed.knoweb.m2catalogue.query;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What a till's scan layer passes to the lookup (22A section 5, GET /v1/catalogue/lookup): the
 * code as scanned ({@code barcode}), or the GTIN with the lot and expiry it parsed from a 2D code
 * ({@code gtin}, {@code lot}, {@code expiry}). {@code locationId} is the shop that scans; the
 * assortment's sell-through flag is read for it once location_assortment exists (M2-09).
 */
public record BarcodeLookup(
        String barcode, String gtin, String lot, LocalDate expiry, String symbology, UUID locationId) {}
