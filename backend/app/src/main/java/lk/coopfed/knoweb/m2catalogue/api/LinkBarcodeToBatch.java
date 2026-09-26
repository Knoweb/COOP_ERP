package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/** Points an ACTIVE barcode at one batch of its SKU (doc 22 section 3.3, "Batch link"). */
public record LinkBarcodeToBatch(UUID skuId, String barcode, String symbology, UUID batchId) {}
