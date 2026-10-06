package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * AddClaimPhoto (doc 24 section 4.5, "photographs attached; pending upload allowed"): the buyer
 * asks the kernel for an upload URL for one photograph of its raised claim (19A section 9).
 */
public record AddClaimPhoto(UUID claimId, String contentType, Long contentLength) {}
