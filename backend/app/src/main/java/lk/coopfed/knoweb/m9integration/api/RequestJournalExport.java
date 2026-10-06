package lk.coopfed.knoweb.m9integration.api;

import java.time.LocalDate;

/**
 * RequestJournalExport (29A section 6; wave 2, CR-29-1 item 1): the journal of the caller's entity
 * for a period, for the accounting package, as CSV. It takes every posting of the period that no
 * earlier export took, so a posting is never exported twice and a later export over the same
 * period is the supplement.
 *
 * @param provisional the caller knows the period is not closed ({@code periodTo} is today or
 *     later in the business time zone) and wants the file anyway, marked PROVISIONAL in the list,
 *     the file name and the file. Without it such a period is refused ({@code
 *     m9.journal.period_open}). A provisional export takes its postings once, like any other; what
 *     arrives later for its period is a supplement, never a superseding re-export.
 */
public record RequestJournalExport(LocalDate periodFrom, LocalDate periodTo, boolean provisional) {

    /** A final export: the period must be closed. */
    public RequestJournalExport(LocalDate periodFrom, LocalDate periodTo) {
        this(periodFrom, periodTo, false);
    }
}
