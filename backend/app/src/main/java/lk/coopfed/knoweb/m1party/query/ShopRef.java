package lk.coopfed.knoweb.m1party.query;

import java.util.UUID;

/** A shop by id only: its owning entity and its location (for a change-log target). */
public record ShopRef(UUID ownerEntityId, UUID locationId) {}
