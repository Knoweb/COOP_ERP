package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/** Suspends an active device: lost, stolen or faulty (doc 21 section 4.5). */
public record SuspendDevice(UUID deviceId, String reasonCode, String reasonText) {}
