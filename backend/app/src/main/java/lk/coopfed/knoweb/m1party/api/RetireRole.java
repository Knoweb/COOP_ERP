package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/** Retires a role that nobody holds any more (21A section 6, RetireRole). */
public record RetireRole(UUID roleId) {}
