package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;

/**
 * DraftNewVersion (23A section 7): a new DRAFT version of a PUBLISHED or SUPERSEDED list. The
 * draft starts empty: carrying the source's lines forward is deferred for the demo (M3-04).
 *
 * @param sourcePriceListId the version the new one follows
 */
public record DraftNewVersion(UUID sourcePriceListId) {}
