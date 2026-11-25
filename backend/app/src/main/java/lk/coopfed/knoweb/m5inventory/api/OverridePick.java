package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** Replace one immutable FEFO-suggested pick with another lot before dispatch. */
public record OverridePick(UUID deliveryNoteId, UUID pickLineId, UUID replacementStockLotId, String reason) {}
