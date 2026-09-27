package lk.coopfed.knoweb.m2catalogue.query;

import java.util.UUID;

/** One row of a SKU's barcode registry (doc 22 section 3.3), ACTIVE or RETIRED. */
public record BarcodeView(String barcode, String symbology, String uomCode, UUID batchId, String status) {}
