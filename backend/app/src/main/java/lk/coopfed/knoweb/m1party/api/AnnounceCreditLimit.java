package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Publishes {@code credit_limit.changed.v1} (null to the limit) once for an ACTIVE relationship
 * activated before ActivateRelationship published it (wave 2, CR-21A-7; the M8 decision D6).
 * Sent by the backfill job in the seller's name, never over HTTP. It changes no term: the limit
 * was set, and audited, when the relationship was opened.
 */
public record AnnounceCreditLimit(UUID relationshipId) {}
