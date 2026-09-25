package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

public record RegisterBarcode(String barcode, String symbology, UUID skuId, String uom, UUID batchId) {}
