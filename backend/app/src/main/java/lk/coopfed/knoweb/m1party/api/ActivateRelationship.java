package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/** Activate a DRAFT relationship (21A section 6, ActivateRelationship). */
public record ActivateRelationship(UUID relationshipId) {}
