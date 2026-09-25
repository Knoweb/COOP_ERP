package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Removes a separation-of-duties pair the caller's entity added itself. A federation default is
 * never removed: an entity may make the rules stricter, not looser.
 */
public record RemoveSodPair(UUID sodPairId) {}
