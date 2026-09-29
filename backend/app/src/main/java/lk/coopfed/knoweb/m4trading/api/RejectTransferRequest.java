package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/** RejectLateralTransfer (doc 24 section 4.7): the society refuses, with a reason. */
public record RejectTransferRequest(UUID requestId, String reason) {}
