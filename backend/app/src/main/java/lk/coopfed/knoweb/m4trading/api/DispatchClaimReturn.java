package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * DispatchClaimReturn: the buyer sends back the goods of a claim the seller approved with the
 * return required. M5 takes them out of the buyer's stock at the GRN's location
 * (RETURN_TO_SELLER, 25A section 6.2) on {@code claim.return_dispatched.v1}.
 */
public record DispatchClaimReturn(UUID claimId) {}
