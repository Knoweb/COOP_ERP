package lk.coopfed.knoweb.m1party.query;

import java.util.UUID;

/** A till position of a location; {@code primary} when it is the location's primary till. */
public record TillPositionView(UUID tillPositionId, UUID locationId, int positionNo, String status, boolean primary) {}
