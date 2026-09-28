package lk.coopfed.knoweb.m3pricing.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * RescindControlPrice (23A section 7; doc 23 section 4.3): a control price ends early, by the
 * gazette that withdraws it. The row is not removed: its {@code effective_to} is set, and the
 * reason and the reference are kept in the audit record.
 *
 * @param lastDay          the last day the ceiling holds (not before its first day)
 * @param gazetteReference the gazette that rescinds it
 */
public record RescindControlPrice(UUID controlPriceId, LocalDate lastDay, String reason, String gazetteReference) {}
