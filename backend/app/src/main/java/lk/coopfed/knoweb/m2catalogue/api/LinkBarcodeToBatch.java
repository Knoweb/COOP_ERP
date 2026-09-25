package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

public record LinkBarcodeToBatch(
        UUID barcodeId,
        UUID batchId
) {}
