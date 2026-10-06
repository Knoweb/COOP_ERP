package lk.coopfed.knoweb.kernel.internal.sync;

import java.time.Duration;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The limits and windows of the sync contract, read from the configuration register
 * (seed/kernel/config-items.yaml, keys sync.*): doc 32 marks them as decision requests (DR-1
 * batch limits, DR-4 change-log retention, DR-5 chunk size), so none is a constant in code
 * (AGENTS.md). The defaults here are the register's defaults, for a register that has not been
 * seeded yet.
 */
@Component
class SyncSettings {

    /** Doc 32 DR-1: 2 MB compressed, the register's default. */
    static final long DR1_MAX_BATCH_BYTES = 2L * 1024 * 1024;

    private final ConfigRegistry config;

    SyncSettings(ConfigRegistry config) {
        this.config = config;
    }

    int maxEvents(ScopeContext scope) {
        return config.getInt("sync.batch.max_events", scope, 500);
    }

    long maxBatchBytes(ScopeContext scope) {
        return config.getInt("sync.batch.max_bytes", scope, (int) DR1_MAX_BATCH_BYTES);
    }

    int maxEventBytes(ScopeContext scope) {
        return config.getInt("sync.event.max_bytes", scope, 256 * 1024);
    }

    int chunkSize(ScopeContext scope) {
        return Math.max(1, config.getInt("sync.ingest.chunk_size", scope, 50));
    }

    Duration inFlightTimeout(ScopeContext scope) {
        return config.getDuration("sync.batch.in_flight_timeout", scope, Duration.ofMinutes(2));
    }

    int gapAnomalyAfter(ScopeContext scope) {
        return config.getInt("sync.gap.anomaly_after", scope, 3);
    }

    String appVersionFloor(ScopeContext scope) {
        return config.getOrDefault("sync.app_version_floor", scope, "0.0.0");
    }

    /** Doc 31 section 6: how long a device below a raised floor keeps syncing (default 14 days). */
    Duration appVersionFloorGrace(ScopeContext scope) {
        return config.getDuration("sync.app_version_floor.grace", scope, Duration.ofDays(14));
    }

    Duration clockDriftReviewAfter(ScopeContext scope) {
        return config.getDuration("sync.clock_drift.review_after", scope, Duration.ofMinutes(10));
    }

    Duration changeLogRetention(ScopeContext scope) {
        return config.getDuration("sync.change_log.retention", scope, Duration.ofDays(30));
    }

    /**
     * Doc 32 DR-4 applied to the log itself: the nightly purge keeps at least this much, never
     * less than the retention a delta may reach back.
     */
    Duration changeLogRetentionFederationWide() {
        return config.getDuration("sync.change_log.retention", null, Duration.ofDays(30));
    }

    Duration enrolmentCodeTtl(ScopeContext scope) {
        return config.getDuration("sync.enrolment_code.ttl", scope, Duration.ofHours(24));
    }

    /** Doc 32 section 9, per device: the batches a device may send in a minute. */
    int batchesPerMinute(ScopeContext scope) {
        return Math.max(1, config.getInt("sync.rate.batches_per_minute", scope, 20));
    }

    /** Doc 32 section 9, per device: the bytes (as sent) a device may send in an hour. */
    long bytesPerHour(ScopeContext scope) {
        return Math.max(1, config.getInt("sync.rate.bytes_per_hour", scope, 16 * 1024 * 1024));
    }

    /**
     * Wave 2, TWK-25: the device's other calls (snapshot, change page, heartbeat, presign) in a
     * minute. A till heartbeats every five minutes and after every batch; 30 is far above that.
     */
    int requestsPerMinute(ScopeContext scope) {
        return Math.max(1, config.getInt("sync.rate.requests_per_minute", scope, 30));
    }

    /** Wave 2, CR-32-1 item 2: how long the raw event of a resolved quarantine row is kept. */
    Duration quarantineRawRetention() {
        return Duration.ofDays(Math.max(1, config.getInt("sync.quarantine.raw_retention_days", null, 30)));
    }

    /** Doc 32 section 9, global: the batches one instance ingests at the same time. */
    int maxConcurrentBatches(ScopeContext scope) {
        return Math.max(1, config.getInt("sync.ingest.max_concurrent", scope, 32));
    }
}
