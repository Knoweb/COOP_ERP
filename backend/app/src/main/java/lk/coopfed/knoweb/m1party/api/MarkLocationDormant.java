package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

public record MarkLocationDormant(UUID locationId, String reasonCode, String reasonText) {}
