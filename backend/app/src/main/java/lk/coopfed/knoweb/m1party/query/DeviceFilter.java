package lk.coopfed.knoweb.m1party.query;

import java.util.UUID;

/** What the device list may be narrowed by; every field is optional. */
public record DeviceFilter(UUID locationId, String status) {}
