package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Changes a user's details: the name shown, the language and the kind among BACK_OFFICE, TILL
 * and BOTH. The user name is the login and does not change; a user never becomes or stops
 * being EXTERNAL.
 */
public record UpdateUser(UUID userId, String displayName, String language, String userKind) {}
