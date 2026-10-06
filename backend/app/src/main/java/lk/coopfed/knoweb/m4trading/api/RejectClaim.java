package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/** RejectClaim (24A section 6; doc 24 section 4.5): the seller refuses the claim, with a reason. */
public record RejectClaim(UUID claimId, String reason) {}
