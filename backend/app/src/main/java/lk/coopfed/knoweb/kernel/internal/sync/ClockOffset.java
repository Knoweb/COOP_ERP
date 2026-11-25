package lk.coopfed.knoweb.kernel.internal.sync;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * Corrects uploaded offline till event timestamps using authenticated device clock offset
 * information (K-13, doc 19A section 13; doc 26 section 3.10; doc 32 section 3).
 *
 * <p>Precedence for current offset resolution:
 * <ol>
 *   <li>The authenticated upload batch's {@code device_clock} measurement, when available
 *       ({@code server_time - device_clock}).</li>
 *   <li>The stored offset for the authenticated device in {@code kernel.device_heartbeat}
 *       when the batch value is absent.</li>
 *   <li>No correction (null) when neither is available.</li>
 * </ol>
 *
 * <p>Correction semantics:
 * <ul>
 *   <li>Events marked {@code offline_timed = true}: uncorrected at till time; apply the measured
 *       current offset once.</li>
 *   <li>Events stamped with {@code clock_offset_ms}: an offset was already applied at event creation;
 *       apply only the difference {@code (current_offset - event_offset)} to prevent double correction
 *       while tracking drift accumulated since event time.</li>
 *   <li>Legacy events without metadata: preserve incoming {@code occurred_at} exactly.</li>
 *   <li>Inconsistent combinations (e.g. marked offline-timed with non-zero offset): safely preserve
 *       {@code occurred_at} without modification.</li>
 *   <li>{@code occurred_local} evidence is preserved without mutation across all cases.</li>
 * </ul>
 */
@Component
public class ClockOffset {

    private final JdbcOperations jdbc;
    private final SystemScope system;

    @Autowired
    public ClockOffset(JdbcOperations jdbc, SystemScope system) {
        this.jdbc = jdbc;
        this.system = system;
    }

    public ClockOffset(JdbcOperations jdbc) {
        this(jdbc, null);
    }

    /**
     * Resolves the current measured clock offset for the device (server_time minus device_clock)
     * using batch device clock first, then falling back to stored heartbeat offset within the device's scope.
     */
    public Long resolveCurrentOffset(ScopeContext device, Instant deviceClock, Instant serverTime) {
        if (deviceClock != null && serverTime != null) {
            return Duration.between(deviceClock, serverTime).toMillis();
        }
        if (device == null || device.deviceId() == null) {
            return null;
        }
        if (system != null) {
            return system.inOwnTransaction(device, () -> readHeartbeatOffset(device.deviceId()));
        }
        return readHeartbeatOffset(device.deviceId());
    }

    /**
     * Resolves current offset by device ID (for unit tests or when ScopeContext is not available).
     */
    public Long resolveCurrentOffset(UUID deviceId, Instant deviceClock, Instant serverTime) {
        if (deviceClock != null && serverTime != null) {
            return Duration.between(deviceClock, serverTime).toMillis();
        }
        if (deviceId == null) {
            return null;
        }
        return readHeartbeatOffset(deviceId);
    }

    private Long readHeartbeatOffset(UUID deviceId) {
        List<Long> stored = jdbc.queryForList(
                "select clock_offset_ms from kernel.device_heartbeat where device_id = ?", Long.class, deviceId);
        if (!stored.isEmpty() && stored.getFirst() != null) {
            return stored.getFirst();
        }
        return null;
    }

    /**
     * Computes the corrected timestamp for an event based on its event-time metadata and the
     * central current measured offset.
     *
     * @param occurredAt the incoming timestamp (not null)
     * @param offlineTimed true if recorded offline without active offset
     * @param eventOffsetMs the offset applied at event creation time, if any
     * @param currentOffsetMs the current measured offset (server_time - device_clock), if any
     * @return the corrected timestamp, retaining original precision and avoiding double correction
     */
    public Instant correct(Instant occurredAt, Boolean offlineTimed, Long eventOffsetMs, Long currentOffsetMs) {
        if (occurredAt == null) {
            return null;
        }
        if (currentOffsetMs == null) {
            return occurredAt;
        }
        if (Boolean.TRUE.equals(offlineTimed)) {
            if (eventOffsetMs != null && eventOffsetMs != 0L) {
                // Inconsistent metadata: marked offline-timed (uncorrected) but stamped with non-zero offset.
                // Safely preserve incoming occurred_at per contract.
                return occurredAt;
            }
            try {
                return occurredAt.plusMillis(currentOffsetMs);
            } catch (DateTimeException | ArithmeticException overflow) {
                return occurredAt;
            }
        }
        if (eventOffsetMs != null) {
            try {
                long delta = Math.subtractExact(currentOffsetMs, eventOffsetMs);
                return occurredAt.plusMillis(delta);
            } catch (DateTimeException | ArithmeticException overflow) {
                return occurredAt;
            }
        }
        // Legacy event without reliable metadata: preserve incoming occurred_at.
        return occurredAt;
    }
}
