package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

class ClockOffsetTest {

    private JdbcOperations jdbc;
    private ClockOffset clockOffset;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcOperations.class);
        clockOffset = new ClockOffset(jdbc);
    }

    @Nested
    @DisplayName("Timestamp correction logic")
    class CorrectionTests {

        @Test
        @DisplayName("Offline event with positive drift: shift timestamp forward by measured offset")
        void offlineEventWithPositiveDrift() {
            Instant occurredAt = Instant.parse("2026-10-14T10:00:00Z");
            // Device clock is slow: server_time - device_clock = +5000ms
            Instant corrected = clockOffset.correct(occurredAt, true, null, 5_000L);

            assertThat(corrected).isEqualTo(Instant.parse("2026-10-14T10:00:05Z"));
        }

        @Test
        @DisplayName("Offline event with negative drift: shift timestamp backward by measured offset")
        void offlineEventWithNegativeDrift() {
            Instant occurredAt = Instant.parse("2026-10-14T10:00:00Z");
            // Device clock is fast: server_time - device_clock = -5000ms
            Instant corrected = clockOffset.correct(occurredAt, true, null, -5_000L);

            assertThat(corrected).isEqualTo(Instant.parse("2026-10-14T09:59:55Z"));
        }

        @Test
        @DisplayName("Already-corrected online event with matching offset: no double correction")
        void alreadyCorrectedOnlineEventWithMatchingOffset() {
            // Event was created with +5000ms offset applied at till time
            Instant occurredAt = Instant.parse("2026-10-14T10:00:05Z");
            // At upload, measured offset is still +5000ms
            Instant corrected = clockOffset.correct(occurredAt, false, 5_000L, 5_000L);

            assertThat(corrected).isEqualTo(occurredAt);
        }

        @Test
        @DisplayName("Event with previous offset and accumulated drift: apply delta only")
        void eventWithPreviousOffsetAndAccumulatedDrift() {
            // Event created with +5000ms offset
            Instant occurredAt = Instant.parse("2026-10-14T10:00:05Z");
            // Device drifted another +2000ms before upload: current offset is +7000ms
            Instant correctedForward = clockOffset.correct(occurredAt, false, 5_000L, 7_000L);
            assertThat(correctedForward).isEqualTo(Instant.parse("2026-10-14T10:00:07Z"));

            // Clock drift reduced before upload: current offset is +3000ms (delta = -2000ms)
            Instant correctedBackward = clockOffset.correct(occurredAt, false, 5_000L, 3_000L);
            assertThat(correctedBackward).isEqualTo(Instant.parse("2026-10-14T10:00:03Z"));
        }

        @Test
        @DisplayName("Legacy event without metadata: retain original occurred_at exactly")
        void legacyEventWithoutMetadata() {
            Instant occurredAt = Instant.parse("2026-10-14T10:00:00Z");
            // offline_timed is null and clock_offset_ms is null
            Instant corrected = clockOffset.correct(occurredAt, null, null, 10_000L);

            assertThat(corrected).isEqualTo(occurredAt);

            // offline_timed is false and clock_offset_ms is null
            Instant correctedFalse = clockOffset.correct(occurredAt, false, null, 10_000L);
            assertThat(correctedFalse).isEqualTo(occurredAt);
        }

        @Test
        @DisplayName("Missing current offset: return original occurred_at for all event types")
        void missingCurrentOffset() {
            Instant occurredAt = Instant.parse("2026-10-14T10:00:00Z");

            assertThat(clockOffset.correct(occurredAt, true, null, null)).isEqualTo(occurredAt);
            assertThat(clockOffset.correct(occurredAt, false, 5_000L, null)).isEqualTo(occurredAt);
            assertThat(clockOffset.correct(occurredAt, null, null, null)).isEqualTo(occurredAt);
        }

        @Test
        @DisplayName("Zero offset: correct calculation with zero values")
        void zeroOffset() {
            Instant occurredAt = Instant.parse("2026-10-14T10:00:00Z");

            // Offline event with zero measured offset
            assertThat(clockOffset.correct(occurredAt, true, null, 0L)).isEqualTo(occurredAt);

            // Online event with zero event-offset and zero current-offset
            assertThat(clockOffset.correct(occurredAt, false, 0L, 0L)).isEqualTo(occurredAt);

            // Online event with +5000ms event-offset, but current-offset is 0 (delta = -5000ms)
            assertThat(clockOffset.correct(occurredAt, false, 5_000L, 0L))
                    .isEqualTo(Instant.parse("2026-10-14T09:59:55Z"));
        }

        @Test
        @DisplayName("Inconsistent metadata combinations: safely preserve incoming occurred_at")
        void inconsistentMetadataCombinations() {
            Instant occurredAt = Instant.parse("2026-10-14T10:00:00Z");

            // Marked offline-timed (uncorrected) but stamped with non-zero applied offset
            Instant result = clockOffset.correct(occurredAt, true, 5_000L, 10_000L);
            assertThat(result).isEqualTo(occurredAt);

            // Marked offline-timed with 0 offset is consistent with uncorrected
            Instant zeroOffsetResult = clockOffset.correct(occurredAt, true, 0L, 10_000L);
            assertThat(zeroOffsetResult).isEqualTo(Instant.parse("2026-10-14T10:00:10Z"));
        }

        @Test
        @DisplayName("Midnight, month, and year rollover: arithmetic preserves correct UTC boundary crossing")
        void rolloverHandling() {
            // Midnight and month rollover: Oct 31 23:59:58 + 5s -> Nov 01 00:00:03
            Instant endOfMonth = Instant.parse("2026-10-31T23:59:58Z");
            assertThat(clockOffset.correct(endOfMonth, true, null, 5_000L))
                    .isEqualTo(Instant.parse("2026-11-01T00:00:03Z"));

            // Year rollover: Dec 31 23:59:58 + 5s -> Jan 01 00:00:03
            Instant endOfYear = Instant.parse("2026-12-31T23:59:58Z");
            assertThat(clockOffset.correct(endOfYear, true, null, 5_000L))
                    .isEqualTo(Instant.parse("2027-01-01T00:00:03Z"));

            // Backward midnight rollover: Nov 01 00:00:02 - 5s -> Oct 31 23:59:57
            Instant startOfMonth = Instant.parse("2026-11-01T00:00:02Z");
            assertThat(clockOffset.correct(startOfMonth, true, null, -5_000L))
                    .isEqualTo(Instant.parse("2026-10-31T23:59:57Z"));
        }

        @Test
        @DisplayName("Millisecond and sub-second precision preservation")
        void millisecondPrecisionPreservation() {
            // Timestamp with millisecond and microsecond precision
            Instant subSecond = Instant.parse("2026-10-14T10:00:00.123456Z");
            Instant corrected = clockOffset.correct(subSecond, true, null, 2_500L);

            assertThat(corrected).isEqualTo(Instant.parse("2026-10-14T10:00:02.623456Z"));
            assertThat(corrected.getNano()).isEqualTo(623_456_000);
        }

        @Test
        @DisplayName("Arithmetic overflow protection: safely preserve occurred_at without exception")
        void arithmeticOverflowProtection() {
            Instant occurredAt = Instant.parse("2026-10-14T10:00:00Z");

            // Subtraction overflow in delta calculation
            Instant result = clockOffset.correct(occurredAt, false, Long.MIN_VALUE, Long.MAX_VALUE);
            assertThat(result).isEqualTo(occurredAt);

            // Addition overflow in Instant
            Instant nearMax = Instant.MAX.minus(Duration.ofDays(1));
            Instant resultOverflow = clockOffset.correct(nearMax, true, null, Long.MAX_VALUE);
            assertThat(resultOverflow).isEqualTo(nearMax);
        }

        @Test
        @DisplayName("Null occurred_at safely returns null")
        void nullOccurredAt() {
            assertThat(clockOffset.correct(null, true, null, 5_000L)).isNull();
        }
    }

    @Nested
    @DisplayName("Current offset resolution")
    class ResolutionTests {

        private final UUID deviceId = UUID.randomUUID();
        private final Instant serverTime = Instant.parse("2026-10-14T10:00:00Z");

        @Test
        @DisplayName("Batch device_clock measurement takes precedence over stored heartbeat")
        void batchClockPrecedence() {
            Instant deviceClock = Instant.parse("2026-10-14T09:59:55Z"); // device slow by 5s
            when(jdbc.queryForList(any(), eq(Long.class), eq(deviceId))).thenReturn(List.of(99_999L));

            Long offset = clockOffset.resolveCurrentOffset(deviceId, deviceClock, serverTime);

            assertThat(offset).isEqualTo(5_000L);
        }

        @Test
        @DisplayName("Fallback to stored heartbeat offset when batch device_clock is absent")
        void fallbackToStoredHeartbeat() {
            when(jdbc.queryForList(any(), eq(Long.class), eq(deviceId))).thenReturn(List.of(12_345L));

            Long offset = clockOffset.resolveCurrentOffset(deviceId, null, serverTime);

            assertThat(offset).isEqualTo(12_345L);
        }

        @Test
        @DisplayName("No correction when neither batch device_clock nor heartbeat is available")
        void neitherAvailable() {
            when(jdbc.queryForList(any(), eq(Long.class), eq(deviceId))).thenReturn(List.of());

            Long offset = clockOffset.resolveCurrentOffset(deviceId, null, serverTime);

            assertThat(offset).isNull();
        }
    }
}
