package lk.coopfed.knoweb.kernel.internal.document;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The density check of C-I2 (19A section 7, GapCheckJob): every series holds exactly the
 * numbers it has handed out. A rolled-back issuance hands out none (the counter rolls back
 * with it), so every hole is a finding, and doc 18 says a gap is "an anomaly, never silently
 * absorbed".
 *
 * <p>{@link #findGaps} reads under the caller's scope, so the nightly run reads as a
 * federation-wide viewer and sees every series. What it finds is logged at WARN here; the
 * REVIEW audit record ({@code NUMBERING_GAP}) and the exception "unless explained by
 * sync_quarantine" need a scoped system job and the quarantine table, which K-12 and K-08
 * bring: the job runner gives a system job the scope an audit record needs, and the
 * quarantine says which holes belong to a till that has not synchronised yet.
 */
@Component
public class GapCheck {

    private static final Logger log = LoggerFactory.getLogger(GapCheck.class);

    /** A series whose issued documents do not fill its counter. */
    public record Gap(UUID seriesId, String prefix, long expectedCount, long foundCount, long firstMissing) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public GapCheck(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
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

    /** Nightly, after the day-close window: every series, as a federation-wide viewer. */
    @Scheduled(cron = "0 40 0 * * *", zone = "UTC")
    public void nightly() {
        List<Gap> gaps = transaction.execute(status -> {
            jdbc.queryForList("select set_config('app.scope_class', 'FEDERATION_VIEW', true),"
                    + " set_config('app.scope_entity_id', '', true),"
                    + " set_config('app.scope_location_id', '', true),"
                    + " set_config('app.granted_entities', '{}', true)");
            return findGaps();
        });

        if (gaps == null || gaps.isEmpty()) {
            log.info("Numbering gap check: every series is dense");
            return;
        }

        for (Gap gap : gaps) {
            log.warn(
                    "NUMBERING_GAP series {} ({}): {} issued of {} numbered, first missing {}",
                    gap.seriesId(),
                    gap.prefix(),
                    gap.foundCount(),
                    gap.expectedCount(),
                    gap.firstMissing());
        }
    }
}
