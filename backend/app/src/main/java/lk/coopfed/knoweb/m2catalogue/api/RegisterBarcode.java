package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/**
 * A barcode of a SKU in a unit (doc 22 section 3.3). {@code symbology} is one of EAN13, EAN8, UPCA,
 * GS1_128, GS1_DATAMATRIX, GS1_QR or INTERNAL; {@code batchId} is set when the code identifies one
 * batch.
 */
public record RegisterBarcode(UUID skuId, String barcode, String symbology, String uomCode, UUID batchId) {}
