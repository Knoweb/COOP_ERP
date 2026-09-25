package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/** Retires an unassigned device for good, once it is wiped (doc 21 section 4.5). */
public record RetireDevice(UUID deviceId, String reasonCode, String reasonText) {}
