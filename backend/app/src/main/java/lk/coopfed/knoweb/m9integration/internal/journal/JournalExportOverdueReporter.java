package lk.coopfed.knoweb.m9integration.internal.journal;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records an overdue review without modifying the journal export. */
@Component
public class JournalExportOverdueReporter {

    private static final String THRESHOLD = "integration.journal_unacknowledged_review_days";

    private final JournalExportOverdueStore store;
    private final AuditReviewReader reviews;
    private final ConfigRegistry config;
    private final AuditFacade audit;
    private final Clock clock;

    public JournalExportOverdueReporter(
            JournalExportOverdueStore store,
            AuditReviewReader reviews,
            ConfigRegistry config,
            AuditFacade audit,
            Clock clock) {
        this.store = store;
        this.reviews = reviews;
        this.config = config;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean report(JournalExportOverdueStore.Candidate candidate, ScopeContext scope) {

        // Recheck after locking. The accountant may have acknowledged
        // the export since the scheduled job discovered it.
        Optional<JournalExportOverdueStore.Candidate> current = store.findByIdForUpdate(candidate.exportId(), scope);

        if (current.isEmpty() || !current.get().generatedAt().equals(candidate.generatedAt())) {
            return false;
        }

        int thresholdDays = config.getInt(THRESHOLD, scope, 30);

        if (candidate.generatedAt().plus(Duration.ofDays(thresholdDays)).isAfter(clock.instant())) {
            return false;
        }

        if (reviews.reviewExists(
                JournalExportOverdueJob.AUDIT_UNACKNOWLEDGED,
                candidate.exportId(),
                "generatedAt",
                candidate.generatedAt().toString(),
                scope)) {
            return false;
        }

        long elapsedDays = ChronoUnit.DAYS.between(candidate.generatedAt(), clock.instant());

        audit.record(
                JournalExportOverdueJob.AUDIT_UNACKNOWLEDGED,
                Subject.of("journal_export", candidate.exportId()),
                null,
                Map.of(
                        "generatedAt", candidate.generatedAt().toString(),
                        "elapsedDays", elapsedDays,
                        "thresholdDays", thresholdDays),
                scope,
                "Journal export remained unacknowledged beyond the configured threshold");

        return true;
    }
}
