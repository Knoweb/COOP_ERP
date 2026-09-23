package lk.coopfed.knoweb.m3pricing.api;

/**
 * The command "register a price list". A command is an immutable record: what the caller
 * wants, nothing about who the caller is.
 *
 * <p>Deviation from 17A section 12, which lists an {@code entityId} field: the owning entity
 * is taken from the caller's scope, never from the request. A client must not be able to
 * name the entity it writes for; the guide's own handler does the same.
 *
 * @param textEn the price list in English; required
 * @param textSi the price list in Sinhala; null when not translated yet
 * @param textTa the price list in Tamil; null when not translated yet
 */
public record RegisterPriceList(String textEn, String textSi, String textTa) {}
