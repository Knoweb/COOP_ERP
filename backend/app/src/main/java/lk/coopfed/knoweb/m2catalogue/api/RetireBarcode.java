package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

public record RetireBarcode(
        UUID barcodeId,
        String reason
) {}
