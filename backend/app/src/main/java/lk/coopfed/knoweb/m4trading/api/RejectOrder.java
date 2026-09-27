package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/** RejectOrder (24A section 6): the seller refuses a submitted order, with a reason. */
public record RejectOrder(UUID orderId, String reasonCode, String reasonText) {}
