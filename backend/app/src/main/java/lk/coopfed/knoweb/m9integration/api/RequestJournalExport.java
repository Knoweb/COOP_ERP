package lk.coopfed.knoweb.m9integration.api;

import java.time.LocalDate;

/**
 * RequestJournalExport (29A section 6): the journal of the caller's entity for a period, for the
 * accounting package, as CSV. It takes every posting of the period that no earlier export took,
 * so a posting is never exported twice and a later export over the same period is the supplement.
 */
public record RequestJournalExport(LocalDate periodFrom, LocalDate periodTo) {}
