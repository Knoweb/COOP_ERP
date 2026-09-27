package lk.coopfed.knoweb.m3pricing.api;

/**
 * CreatePriceList (23A section 7; doc 23 section 5.1): a new list, version 1, DRAFT, of the
 * caller's entity. The owner is the caller's scope entity, never a field of the request.
 *
 * @param kind TRADE (RETAIL and ADVISORY are refused until their tickets: deferred for the demo)
 * @param name the list's name, as the owner calls it
 */
public record CreatePriceList(String kind, String name) {}
