package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;

/**
 * DraftNewVersion (23A section 7): a new DRAFT version of a PUBLISHED or SUPERSEDED list. The
 * draft carries the chosen source version's lines forward for editing.
 *
 * @param sourcePriceListId the version the new one follows
 */
public record DraftNewVersion(UUID sourcePriceListId) {}
