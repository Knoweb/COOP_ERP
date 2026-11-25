package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** CancelTransfer (doc 25 section 4.5): cancel before receipt and negate TRANSFER_OUT. */
public record CancelTransfer(UUID transferId, String reason, boolean physicalReturnVerified) {}
