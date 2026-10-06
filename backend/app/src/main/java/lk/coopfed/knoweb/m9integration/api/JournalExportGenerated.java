package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * journal.generated.v1 (29A section 6): an export was generated, balanced, with its totals.
 *
 * @param provisional the period was open when the export was made (wave 2, CR-29-1 item 1)
 */
public record JournalExportGenerated(
        UUID exportId,
        LocalDate periodFrom,
        LocalDate periodTo,
        int lineCount,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        String contentHash,
        boolean provisional)
        implements DomainEvent {

    public static final String TYPE = "journal.generated.v1";
}
