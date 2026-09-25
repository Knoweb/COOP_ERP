package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

public record SetPrimaryTill(UUID locationId, UUID tillPositionId, String reasonCode, String reasonText) {}
