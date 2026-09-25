package lk.coopfed.knoweb.m2catalogue.query;

import java.util.UUID;

public record BarcodeResolution(UUID skuId, String uom, UUID batchId) {}
