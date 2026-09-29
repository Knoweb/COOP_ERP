package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** journal.generated.v1 (29A section 6): an export was generated, balanced, with its totals. */
public record JournalExportGenerated(
        UUID exportId,
        LocalDate periodFrom,
        LocalDate periodTo,
        int lineCount,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        String contentHash)
        implements DomainEvent {

    public static final String TYPE = "journal.generated.v1";
}
