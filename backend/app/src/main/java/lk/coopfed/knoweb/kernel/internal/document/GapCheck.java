package lk.coopfed.knoweb.kernel.internal.document;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The density check of C-I2 (19A section 7, GapCheckJob): every series holds exactly the
 * numbers it has handed out. A rolled-back issuance hands out none (the counter rolls back
 * with it), so every hole is a finding, and doc 18 says a gap is "an anomaly, never silently
 * absorbed". A series is measured against the larger of its counter and its highest stored
 * number, so a series whose documents arrive through ingestion (the counter stays at 1) is
 * checked too. The density of (source, source_seq) that 19A names beside doc_number waits for
 * the ingestion of K-08 part 2, which writes those columns.
 *
 * <p>{@link #findGaps} reads under the caller's scope. The nightly job reads as a
 * federation-wide viewer, so it sees every series, and records each gap as a
 * {@code NUMBERING_GAP} REVIEW audit record in the system scope (K-12) when
 * {@code coop-erp.system.entity-id} is set, and logs it otherwise. The exception "unless
 * explained by sync_quarantine" waits for the quarantine table of K-08.
 */
@Component
public class GapCheck {

    private static final Logger log = LoggerFactory.getLogger(GapCheck.class);

    static final String AUDIT_GAP = "NUMBERING_GAP";

    /** A series whose issued documents do not fill its counter. */
    public record Gap(UUID seriesId, String prefix, long expectedCount, long foundCount, long firstMissing) {}

    private final JdbcTemplate jdbc;
    private final SystemScope system;
    private final AuditFacade audit;

    public GapCheck(JdbcTemplate jdbc, SystemScope system, AuditFacade audit) {
        this.jdbc = jdbc;
        this.system = system;
        this.audit = audit;
    }

    /** The gaps among the series the current scope can see. Call inside a scoped transaction. */
    public List<Gap> findGaps() {
        return jdbc.query(
                "select series_id, prefix, expected_count, found_count, first_missing from kernel.numbering_gaps()",
                (rs, rowNum) -> new Gap(
                        rs.getObject("series_id", UUID.class),
                        rs.getString("prefix"),
                        rs.getLong("expected_count"),
                        rs.getLong("found_count"),
                        rs.getLong("first_missing")));
    }

    /** Nightly, after the day-close window: every series. Critical: a failure is retried at once. */
    @ScheduledJob(
            name = "gap-check",
            cron = "0 40 0 * * *",
            critical = true,
            lockTimeout = "PT30M",
            maxRuntime = "PT15M")
    public int nightly(JobExecution execution) {
        List<Gap> gaps = system.inScope(SystemScope.federationView(), this::findGaps);

        if (gaps.isEmpty()) {
            log.info("Numbering gap check: every series is dense");
            return 0;
        }

        Optional<ScopeContext> scope = execution.systemScope();

        for (Gap gap : gaps) {
            log.warn(
                    "NUMBERING_GAP series {} ({}): {} issued of {} numbered, first missing {}",
                    gap.seriesId(),
                    gap.prefix(),
                    gap.foundCount(),
                    gap.expectedCount(),
                    gap.firstMissing());

            scope.ifPresent(ctx -> system.inScope(ctx, () -> {
                audit.record(
                        AUDIT_GAP,
                        Subject.of("numbering_series", gap.seriesId()),
                        null,
                        Map.of(
                                "prefix", gap.prefix(),
                                "expected", String.valueOf(gap.expectedCount()),
                                "found", String.valueOf(gap.foundCount()),
                                "firstMissing", String.valueOf(gap.firstMissing())),
                        ctx,
                        "Series is not dense");
                return null;
            }));
        }

        return gaps.size();
    }
}
