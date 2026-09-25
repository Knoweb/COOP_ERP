package lk.coopfed.knoweb.m1party.api;

/**
 * Adds a separation-of-duties pair for the caller's entity, or raises one to ROLE mode (21A
 * section 6, SetSodPairMode; doc 19 section 3.2: "entities may raise a pair from INSTANCE to
 * ROLE mode"). The order of the two codes does not matter.
 *
 * @param mode INSTANCE (one person may not do both on the same document) or ROLE (one person
 *             may not hold both at all)
 */
public record SetSodPair(String permissionA, String permissionB, String mode) {}
