package lk.coopfed.knoweb.m3pricing.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * PublishPriceList (23A section 7; doc 23 section 4.1): the DRAFT becomes PUBLISHED from
 * {@code applyFrom}, a business date not in the past; the previous published version of the
 * same list becomes SUPERSEDED.
 */
public record PublishPriceList(UUID priceListId, LocalDate applyFrom) {}
