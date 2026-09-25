package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/** Suspend an ACTIVE relationship (21A section 6, SuspendRelationship); open documents are unaffected. */
public record SuspendRelationship(UUID relationshipId, String reasonCode, String reasonText) {}
