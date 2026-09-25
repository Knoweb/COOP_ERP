package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/** Reinstates a suspended device once it is physically recovered (doc 21 section 4.5). */
public record ReinstateDevice(UUID deviceId, String reasonCode, String reasonText) {}
