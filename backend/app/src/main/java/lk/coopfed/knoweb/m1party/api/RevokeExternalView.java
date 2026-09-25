package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/** Ends an active external grant now (doc 21 section 4.6; flow 6.5: "revocation immediate"). */
public record RevokeExternalView(UUID grantId, String reason) {}
