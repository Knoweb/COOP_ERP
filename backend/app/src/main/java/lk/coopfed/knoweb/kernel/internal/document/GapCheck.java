package lk.coopfed.knoweb.kernel.internal.document;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
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
 * checked too.
 *
 * <p>Beside document numbering, this component also checks the density of (source, source_seq)
 * for device-originated streams (K-05, CR-19A-3). Central sequence numbers (source = 'central')
 * are excluded. Sequences explained by active quarantine or audited resets (sync_sequence_gap)
 * are excluded from gap reporting. Missing device sequence numbers raise a {@code DEVICE_SEQUENCE_GAP}
 * REVIEW audit record.
 *
 * <p>{@link #findGaps} and {@link #findDeviceGaps} read under the caller's scope. The nightly job
 * reads as a federation-wide viewer, so it sees every series and device, and records each gap as a
 * {@code NUMBERING_GAP} or {@code DEVICE_SEQUENCE_GAP} REVIEW audit record in the system scope (K-12)
 * when {@code coop-erp.system.entity-id} is set, and logs it otherwise.
 */
@Component
public class GapCheck {

    private static final Logger log = LoggerFactory.getLogger(GapCheck.class);

    static final String AUDIT_GAP = "NUMBERING_GAP";
    public static final String AUDIT_DEVICE_GAP = "DEVICE_SEQUENCE_GAP";

    /** A series whose issued documents do not fill its counter. */
    public record Gap(UUID seriesId, String prefix, long expectedCount, long foundCount, long firstMissing) {}

    /** A device stream whose ingested events do not fill its sequence range. */
    public record DeviceGap(
            UUID deviceId, String source, UUID ownerEntityId, long expectedCount, long foundCount, long firstMissing) {}

    private final JdbcTemplate jdbc;
    private final SystemScope system;
    private final AuditFacade audit;
    private final AuditReviewReader reviews;

    public GapCheck(JdbcTemplate jdbc, SystemScope system, AuditFacade audit, AuditReviewReader reviews) {
        this.jdbc = jdbc;
        this.system = system;
        this.audit = audit;
        this.reviews = reviews;
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

    /** The device sequence gaps among the devices the current scope can see. Call inside a scoped transaction. */
    public List<DeviceGap> findDeviceGaps() {
        return jdbc.query(
                "select device_id, source, owner_entity_id, expected_count, found_count, first_missing"
                        + " from kernel.device_sequence_gaps()",
                (rs, rowNum) -> new DeviceGap(
                        rs.getObject("device_id", UUID.class),
                        rs.getString("source"),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getLong("expected_count"),
                        rs.getLong("found_count"),
                        rs.getLong("first_missing")));
    }

    /** Records an audited DEVICE_SEQUENCE_GAP review for a device sequence gap, deduplicated and locked. */
    public void recordDeviceGapReview(DeviceGap gap, ScopeContext scope) {
        system.inOwnTransaction(scope, () -> {
            jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", "device-sequence-gap:" + gap.deviceId());
            if (!reviews.reviewExists(
                    AUDIT_DEVICE_GAP, gap.deviceId(), "firstMissing", String.valueOf(gap.firstMissing()), scope)) {
                audit.record(
                        AUDIT_DEVICE_GAP,
                        Subject.of("device", gap.deviceId()),
                        null,
                        Map.of(
                                "source", gap.source(),
                                "expected", String.valueOf(gap.expectedCount()),
                                "found", String.valueOf(gap.foundCount()),
                                "firstMissing", String.valueOf(gap.firstMissing())),
                        scope,
                        "Device sequence is not dense");
            }
            return null;
        });
    }

    /**
     * Nightly, after the day-close window: every series and device stream. Critical: a failure is retried at once,
     * under the same lock, so the lock outlives two runs.
     */
    @ScheduledJob(
            name = "gap-check",
            cron = "0 40 0 * * *",
            critical = true,
            lockTimeout = "PT35M",
            maxRuntime = "PT15M")
    public int nightly(JobExecution execution) {
        List<Gap> numberingGaps = system.inScope(SystemScope.federationView(), this::findGaps);
        Optional<ScopeContext> scope = execution.systemScope();

        if (numberingGaps.isEmpty()) {
            log.info("Numbering gap check: every series is dense");
        } else {
            for (Gap gap : numberingGaps) {
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
        }

        List<DeviceGap> deviceGaps = system.inScope(SystemScope.federationView(), this::findDeviceGaps);

        if (deviceGaps.isEmpty()) {
            log.info("Device sequence gap check: every device stream is dense");
        } else {
            for (DeviceGap gap : deviceGaps) {
                log.warn(
                        "DEVICE_SEQUENCE_GAP device {} source {}: {} found of {} expected, first missing {}",
                        gap.deviceId(),
                        gap.source(),
                        gap.foundCount(),
                        gap.expectedCount(),
                        gap.firstMissing());

                scope.ifPresent(ctx -> recordDeviceGapReview(gap, ctx));
            }
        }

        return numberingGaps.size() + deviceGaps.size();
    }
}
