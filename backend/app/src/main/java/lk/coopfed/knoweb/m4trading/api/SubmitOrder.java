package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/** SubmitOrder (24A section 6): the buyer issues its draft order from its ENTITY series (24B). */
public record SubmitOrder(UUID orderId) {}
