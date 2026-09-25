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

    private final ConfigRegistry config;

    SyncSettings(ConfigRegistry config) {
        this.config = config;
    }

    int maxEvents(ScopeContext scope) {
        return config.getInt("sync.batch.max_events", scope, 500);
    }

    long maxBatchBytes(ScopeContext scope) {
        return config.getInt("sync.batch.max_bytes", scope, 2 * 1024 * 1024);
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

    Duration clockDriftReviewAfter(ScopeContext scope) {
        return config.getDuration("sync.clock_drift.review_after", scope, Duration.ofMinutes(10));
    }

    Duration changeLogRetention(ScopeContext scope) {
        return config.getDuration("sync.change_log.retention", scope, Duration.ofDays(30));
    }

    Duration enrolmentCodeTtl(ScopeContext scope) {
        return config.getDuration("sync.enrolment_code.ttl", scope, Duration.ofHours(24));
    }
}
