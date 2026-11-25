package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * AddDiscrepancyPhoto (ORD-10, doc 24 section 3.4): the buyer asks the kernel for an upload URL
 * for one photograph of its delivery discrepancy (19A section 9).
 */
public record AddDiscrepancyPhoto(UUID discrepancyId, String contentType, Long contentLength) {}
