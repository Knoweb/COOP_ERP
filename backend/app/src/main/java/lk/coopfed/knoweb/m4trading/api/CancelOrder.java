package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * CancelOrder (24A section 6): the buyer cancels its order, draft or submitted, while nothing of it
 * has been dispatched (the demo cancels the whole order; a partial cancellation of the undispatched
 * remainder is deferred).
 */
public record CancelOrder(UUID orderId, String reasonCode, String reasonText) {}
