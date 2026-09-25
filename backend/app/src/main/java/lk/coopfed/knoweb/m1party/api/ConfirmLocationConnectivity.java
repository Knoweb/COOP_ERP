package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * The connectivity gate of doc 21 section 3.3 (connectivity_spec_met must be true for ACTIVE),
 * recorded as a fact: the audit record says who confirmed it (21A section 8: "a fact, not a
 * toggle").
 */
public record ConfirmLocationConnectivity(UUID locationId) {}
