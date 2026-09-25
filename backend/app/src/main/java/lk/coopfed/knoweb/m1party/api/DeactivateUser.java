package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/** Deactivates a user for good (doc 21 section 4.4: DEACTIVATED is terminal). */
public record DeactivateUser(UUID userId, String reasonCode, String reasonText) {}
