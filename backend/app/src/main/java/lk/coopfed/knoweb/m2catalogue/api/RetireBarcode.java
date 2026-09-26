package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/** Retires an ACTIVE barcode of the caller's own registry row, with a reason (doc 22 section 4.2). */
public record RetireBarcode(UUID skuId, String barcode, String symbology, String reasonCode, String reasonText) {}
