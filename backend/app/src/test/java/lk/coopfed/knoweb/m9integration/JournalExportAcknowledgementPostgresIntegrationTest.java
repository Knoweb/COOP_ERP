package lk.coopfed.knoweb.m9integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.AcknowledgeJournalExport;
import lk.coopfed.knoweb.m9integration.api.JournalExportAcknowledged;
import lk.coopfed.knoweb.m9integration.internal.journal.JournalExportOverdueJob;
import lk.coopfed.knoweb.m9integration.internal.journal.JournalExportOverdueStore;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JournalExportAcknowledgementPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID OWNER = UUID.fromString("0190e9b0-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0190e9b0-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e9b0-0000-7000-8000-000000000003");

    @Autowired
    Handles<AcknowledgeJournalExport, UUID> acknowledge;

    @Autowired
    JournalExportOverdueJob job;

    @Autowired
    JournalExportOverdueStore store;

    @Autowired
    Clock clock;

    @BeforeEach
    void clean() {
        superuserJdbc()
                .execute("truncate table integration.journal_line, "
                        + "integration.journal_export_file, "
                        + "integration.journal_export, "
                        + "integration.journal_posting");
        kernel.reset();
    }

    @Test
    void acknowledgesGeneratedExportExactlyOnce() {
        UUID id = export(OWNER, "GENERATED", 5);

        assertThat(acknowledge.handle(new AcknowledgeJournalExport(id), own(OWNER)))
                .isEqualTo(id);

        var row = superuserJdbc()
                .queryForMap(
                        "select status, acknowledged_by, acknowledged_at "
                                + "from integration.journal_export where export_id = ?",
                        id);

        assertThat(row.get("status")).isEqualTo("ACKNOWLEDGED");
        assertThat(row.get("acknowledged_by")).isEqualTo(USER.toString());
        assertThat(row.get("acknowledged_at")).isNotNull();

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly("JOURNAL_EXPORT_ACKNOWLEDGED");

        assertThat(kernel.committedEvents()).singleElement().isInstanceOf(JournalExportAcknowledged.class);

        kernel.reset();

        assertThat(acknowledge.handle(new AcknowledgeJournalExport(id), own(OWNER)))
                .isEqualTo(id);

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void cannotAcknowledgeAnotherEntitysExport() {
        UUID id = export(OWNER, "GENERATED", 5);

        ProblemException failure = assertThrows(
                ProblemException.class, () -> acknowledge.handle(new AcknowledgeJournalExport(id), own(OTHER)));

        assertThat(failure.messageId()).isEqualTo("m9.journal.not_found");

        assertThat(status(id)).isEqualTo("GENERATED");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void rejectsLocationScopedAcknowledgement() {
        UUID id = export(OWNER, "GENERATED", 5);

        ProblemException failure = assertThrows(
                ProblemException.class,
                () -> acknowledge.handle(new AcknowledgeJournalExport(id), ScopeContext.dev(USER, OWNER, Ids.next())));

        assertThat(failure.messageId()).isEqualTo("m9.journal.entity_required");
        assertThat(status(id)).isEqualTo("GENERATED");
    }

    @Test
    void overdueJobReviewsOnlyOldGeneratedExportsOnce() {
        UUID overdue = export(OWNER, "GENERATED", 31);
        UUID recent = export(OWNER, "GENERATED", 10);
        UUID acknowledged = export(OWNER, "GENERATED", 40);

        acknowledge.handle(new AcknowledgeJournalExport(acknowledged), own(OWNER));
        kernel.reset();

        assertThat(job.review(execution())).isEqualTo(1);

        assertThat(kernel.committedAudit()).extracting(a -> a.subject().id()).containsExactly(overdue);

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly("JOURNAL_EXPORT_UNACKNOWLEDGED");

        assertThat(status(overdue)).isEqualTo("GENERATED");
        assertThat(status(recent)).isEqualTo("GENERATED");
        assertThat(status(acknowledged)).isEqualTo("ACKNOWLEDGED");

        kernel.reset();

        assertThat(job.review(execution())).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void candidateDiscoveryRespectsEntityIsolation() {
        UUID ownExport = export(OWNER, "GENERATED", 31);
        export(OTHER, "GENERATED", 31);

        assertThat(store.findCandidates(own(OWNER)))
                .extracting(JournalExportOverdueStore.Candidate::exportId)
                .containsExactly(ownExport);
    }

    private UUID export(UUID entity, String status, int daysAgo) {
        UUID id = Ids.next();
        Instant generated = clock.instant().minus(daysAgo, ChronoUnit.DAYS);

        superuserJdbc()
                .update(
                        """
                insert into integration.journal_export
                  (export_id, owner_entity_id, period_from, period_to,
                   format, provisional, status, line_count,
                   total_debit, total_credit, content_hash,
                   generated_at, requested_by, requested_at)
                values (?, ?, '2026-08-01', '2026-08-31',
                        'CSV', false, ?, 0, 0, 0,
                        repeat('a', 64), ?, ?, ?)
                """,
                        id,
                        entity,
                        status,
                        Timestamp.from(generated),
                        USER,
                        Timestamp.from(generated));

        return id;
    }

    private String status(UUID id) {
        return superuserJdbc()
                .queryForObject(
                        "select status from integration.journal_export " + "where export_id = ?", String.class, id);
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    private static JobExecution execution() {
        return new JobExecution() {
            @Override
            public UUID runId() {
                return UUID.randomUUID();
            }

            @Override
            public void itemsProcessed(int count) {}

            @Override
            public Optional<ScopeContext> systemScope() {
                return Optional.empty();
            }
        };
    }
}
