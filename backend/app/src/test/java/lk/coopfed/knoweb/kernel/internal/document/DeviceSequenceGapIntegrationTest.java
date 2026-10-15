package lk.coopfed.knoweb.kernel.internal.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class DeviceSequenceGapIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GapCheck gapCheck;

    @Autowired
    private SystemScope system;

    @Autowired
    private AuditReviewReader reviews;

    private static final UUID ENTITY_A = Ids.next();
    private static final UUID ENTITY_B = Ids.next();
    private static final UUID LOCATION_A = Ids.next();

    @BeforeEach
    void cleanState() {
        superuserJdbc().execute("TRUNCATE TABLE kernel.event_outbox");
        superuserJdbc().execute("DELETE FROM kernel.device_sync_cursor");
        superuserJdbc().execute("DELETE FROM kernel.sync_quarantine");
        superuserJdbc().execute("DELETE FROM kernel.sync_sequence_gap");
        superuserJdbc().execute("DELETE FROM kernel.audit_event WHERE event_type_code = 'DEVICE_SEQUENCE_GAP'");
    }

    private void insertCursor(UUID deviceId, UUID ownerEntityId, long lastAppliedSeq) {
        superuserJdbc()
                .update(
                        """
                        INSERT INTO kernel.device_sync_cursor (
                            device_id, owner_entity_id, last_applied_seq, last_applied_at
                        ) VALUES (?, ?, ?, now())
                        ON CONFLICT (device_id) DO UPDATE
                           SET owner_entity_id = EXCLUDED.owner_entity_id,
                               last_applied_seq = EXCLUDED.last_applied_seq,
                               last_applied_at = now()
                        """,
                        deviceId,
                        ownerEntityId,
                        lastAppliedSeq);
    }

    private void insertOutboxEvent(UUID deviceId, long sourceSeq, UUID ownerEntityId, UUID locationId) {
        insertOutboxEvent(deviceId.toString(), sourceSeq, ownerEntityId, locationId);
    }

    private void insertOutboxEvent(String source, long sourceSeq, UUID ownerEntityId, UUID locationId) {
        superuserJdbc()
                .update(
                        """
                        INSERT INTO kernel.event_outbox (
                            event_id, event_type, occurred_at, occurred_local, received_at,
                            source, source_seq, owner_entity_id, location_id,
                            aggregate_type, aggregate_id, correlation_id, payload, published_at
                        ) VALUES (
                            ?, 'receipt.issued.v1', now(), now(), now(),
                            ?, ?, ?, ?,
                            'receipt', ?, ?, '{}'::jsonb, now()
                        )
                        """,
                        Ids.next(),
                        source,
                        sourceSeq,
                        ownerEntityId,
                        locationId,
                        Ids.next(),
                        Ids.next());
    }

    private void insertQuarantine(UUID deviceId, long deviceSeq, UUID ownerEntityId, String resolution) {
        superuserJdbc()
                .update(
                        """
                        INSERT INTO kernel.sync_quarantine (
                            quarantine_id, device_id, owner_entity_id, batch_id, device_seq,
                            event_id, event_type, reason, raw_event, resolution,
                            resolved_at, resolution_reason
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, 'receipt.issued.v1', 'SCHEMA', '{}'::jsonb, ?,
                            CASE WHEN ?::text IS NOT NULL THEN now() ELSE NULL END,
                            CASE WHEN ?::text IS NOT NULL THEN 'Resolved in test' ELSE NULL END
                        )
                        """,
                        Ids.next(),
                        deviceId,
                        ownerEntityId,
                        Ids.next(),
                        deviceSeq,
                        Ids.next(),
                        resolution,
                        resolution,
                        resolution);
    }

    private void insertDocument(UUID seriesId, long docNumber, UUID entityId, UUID locationId) {
        superuserJdbc()
                .update(
                        """
                        INSERT INTO kernel.document (
                            document_id, doc_type_code, series_id, doc_number, doc_number_display,
                            owner_entity_id, location_id, status, issued_at, business_date, content_hash
                        ) VALUES (?, 'RCT', ?, ?, ?, ?, ?, 'ISSUED', now(), current_date, 'hash')
                        """,
                        Ids.next(),
                        seriesId,
                        docNumber,
                        String.valueOf(docNumber),
                        entityId,
                        locationId);
    }

    private void insertSequenceReset(UUID deviceId, UUID ownerEntityId, long fromSeq, long toSeq) {
        superuserJdbc()
                .update(
                        """
                        INSERT INTO kernel.sync_sequence_gap (
                            gap_id, device_id, owner_entity_id, from_seq, to_seq,
                            reason_code, reason_text, recorded_by_user_id, idempotency_key
                        ) VALUES (?, ?, ?, ?, ?, 'DEVICE_STORAGE_LOSS', 'Database reset', ?, ?)
                        """,
                        Ids.next(),
                        deviceId,
                        ownerEntityId,
                        fromSeq,
                        toSeq,
                        Ids.next(),
                        Ids.next().toString());
    }

    private JobExecution jobExecution(UUID systemEntity) {
        return new JobExecution() {
            @Override
            public UUID runId() {
                return Ids.next();
            }

            @Override
            public void itemsProcessed(int count) {}

            @Override
            public Optional<ScopeContext> systemScope() {
                return Optional.of(SystemScope.own(systemEntity, null));
            }
        };
    }

    @Test
    @DisplayName("1. Dense device sequences — no review")
    void denseDeviceSequencesProduceNoGapsAndNoReviews() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 3);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 2, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).isEmpty();

        int processed = gapCheck.nightly(jobExecution(TEST_FEDERATION));
        assertThat(processed).isZero();
    }

    @Test
    @DisplayName("2. Missing internal sequence — review created")
    void missingInternalSequenceDetectsGapAndCreatesReview() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 3);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).hasSize(1);
        GapCheck.DeviceGap gap = gaps.getFirst();
        assertThat(gap.deviceId()).isEqualTo(deviceId);
        assertThat(gap.expectedCount()).isEqualTo(3);
        assertThat(gap.foundCount()).isEqualTo(2);
        assertThat(gap.firstMissing()).isEqualTo(2);

        int processed = gapCheck.nightly(jobExecution(TEST_FEDERATION));
        assertThat(processed).isEqualTo(1);

        ScopeContext fedScope = SystemScope.own(TEST_FEDERATION, null);
        boolean reviewExists = reviews.reviewExists(GapCheck.AUDIT_DEVICE_GAP, deviceId, "firstMissing", "2", fedScope);
        assertThat(reviewExists).isTrue();
    }

    @Test
    @DisplayName("3. Missing first sequence — review created")
    void missingFirstSequenceDetectsGapAndCreatesReview() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 3);
        insertOutboxEvent(deviceId, 2, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).hasSize(1);
        GapCheck.DeviceGap gap = gaps.getFirst();
        assertThat(gap.firstMissing()).isEqualTo(1);
        assertThat(gap.expectedCount()).isEqualTo(3);
        assertThat(gap.foundCount()).isEqualTo(2);

        gapCheck.nightly(jobExecution(TEST_FEDERATION));
        ScopeContext fedScope = SystemScope.own(TEST_FEDERATION, null);
        assertThat(reviews.reviewExists(GapCheck.AUDIT_DEVICE_GAP, deviceId, "firstMissing", "1", fedScope))
                .isTrue();
    }

    @Test
    @DisplayName("4. Missing final sequence — review created")
    void missingFinalSequenceDetectsGapAndCreatesReview() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 4);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 2, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).hasSize(1);
        GapCheck.DeviceGap gap = gaps.getFirst();
        assertThat(gap.firstMissing()).isEqualTo(4);
        assertThat(gap.expectedCount()).isEqualTo(4);
        assertThat(gap.foundCount()).isEqualTo(3);

        gapCheck.nightly(jobExecution(TEST_FEDERATION));
        ScopeContext fedScope = SystemScope.own(TEST_FEDERATION, null);
        assertThat(reviews.reviewExists(GapCheck.AUDIT_DEVICE_GAP, deviceId, "firstMissing", "4", fedScope))
                .isTrue();
    }

    @Test
    @DisplayName("5. Cursor versus outbox maximum mismatch")
    void cursorVersusOutboxMaximumMismatchSpansAuthoritativeMax() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 3);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 2, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 5, ENTITY_A, LOCATION_A); // Outbox has 5 > cursor 3

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).hasSize(1);
        GapCheck.DeviceGap gap = gaps.getFirst();
        assertThat(gap.expectedCount()).isEqualTo(5);
        assertThat(gap.foundCount()).isEqualTo(4);
        assertThat(gap.firstMissing()).isEqualTo(4);
    }

    @Test
    @DisplayName("6. Quarantined sequence — appropriately excluded")
    void quarantinedSequenceExplainsHole() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 3);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertQuarantine(deviceId, 2, ENTITY_A, null); // Unresolved quarantine
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("7. Repaired quarantine — missing event detected")
    void repairedQuarantineDoesNotSuppressGap() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 3);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertQuarantine(deviceId, 2, ENTITY_A, "REPAIRED"); // REPAIRED requires outbox row
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().firstMissing()).isEqualTo(2);
    }

    @Test
    @DisplayName("8. Audited sequence reset — no false review")
    void auditedSequenceResetExplainsSkippedRange() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 6);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 2, ENTITY_A, LOCATION_A);
        insertSequenceReset(deviceId, ENTITY_A, 3, 5);
        insertOutboxEvent(deviceId, 6, ENTITY_A, LOCATION_A);

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("9. Overlapping reset ranges")
    void overlappingResetRangesDoNotDoubleCount() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 25);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertSequenceReset(deviceId, ENTITY_A, 2, 15);
        insertSequenceReset(deviceId, ENTITY_A, 10, 24); // Overlaps 10..15, misses 25

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).hasSize(1);
        GapCheck.DeviceGap gap = gaps.getFirst();
        assertThat(gap.firstMissing()).isEqualTo(25);
        assertThat(gap.expectedCount()).isEqualTo(25);
        assertThat(gap.foundCount()).isEqualTo(24); // 1 + [2..24] merged = 24
    }

    @Test
    @DisplayName("10. Central and non-device sources ignored")
    void centralAndNonDeviceSourcesAreIgnored() {
        insertOutboxEvent("central", 1, TEST_FEDERATION, null);
        insertOutboxEvent("central", 3, TEST_FEDERATION, null); // Central gap at 2 ignored
        insertOutboxEvent("seed-source", 1, TEST_FEDERATION, null);
        insertOutboxEvent("seed-source", 4, TEST_FEDERATION, null); // Non-UUID ignored

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("11. Multi-device isolation")
    void multiDeviceStreamsAreIsolated() {
        UUID deviceA = Ids.next();
        UUID deviceB = Ids.next();

        insertCursor(deviceA, ENTITY_A, 3);
        insertOutboxEvent(deviceA, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceA, 3, ENTITY_A, LOCATION_A); // Gap at 2

        insertCursor(deviceB, ENTITY_A, 3);
        insertOutboxEvent(deviceB, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceB, 2, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceB, 3, ENTITY_A, LOCATION_A); // Dense

        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().deviceId()).isEqualTo(deviceA);
    }

    @Test
    @DisplayName("12. Multi-tenant isolation")
    void multiTenantIsolationIsEnforced() {
        UUID deviceA = Ids.next();
        UUID deviceB = Ids.next();

        insertCursor(deviceA, ENTITY_A, 3);
        insertOutboxEvent(deviceA, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceA, 3, ENTITY_A, LOCATION_A); // Entity A gap at 2

        insertCursor(deviceB, ENTITY_B, 4);
        insertOutboxEvent(deviceB, 1, ENTITY_B, null);
        insertOutboxEvent(deviceB, 2, ENTITY_B, null);
        insertOutboxEvent(deviceB, 4, ENTITY_B, null); // Entity B gap at 3

        // Entity A scope sees only Device A
        List<GapCheck.DeviceGap> gapsA = system.inScope(SystemScope.own(ENTITY_A, null), gapCheck::findDeviceGaps);
        assertThat(gapsA).hasSize(1);
        assertThat(gapsA.getFirst().deviceId()).isEqualTo(deviceA);

        // Entity B scope sees only Device B
        List<GapCheck.DeviceGap> gapsB = system.inScope(SystemScope.own(ENTITY_B, null), gapCheck::findDeviceGaps);
        assertThat(gapsB).hasSize(1);
        assertThat(gapsB.getFirst().deviceId()).isEqualTo(deviceB);

        // FEDERATION_VIEW sees both
        List<GapCheck.DeviceGap> gapsFed = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gapsFed).hasSize(2);
    }

    @Test
    @DisplayName("13. Late-arriving event behavior")
    void lateArrivingEventResolvesGapOnNextCheck() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 3);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);

        // Gap present
        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).hasSize(1);

        // Late event arrives
        insertOutboxEvent(deviceId, 2, ENTITY_A, LOCATION_A);

        // Gap resolved
        List<GapCheck.DeviceGap> gapsAfter = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gapsAfter).isEmpty();
    }

    @Test
    @DisplayName("14. Repeated gap checks — no duplicates")
    void repeatedGapChecksDoNotDuplicateReviews() {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 3);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);

        JobExecution exec = jobExecution(TEST_FEDERATION);
        gapCheck.nightly(exec);
        gapCheck.nightly(exec); // Second execution

        Integer count = superuserJdbc()
                .queryForObject(
                        "SELECT count(*) FROM kernel.audit_event WHERE event_type_code = 'DEVICE_SEQUENCE_GAP' AND subject_id = ?",
                        Integer.class,
                        deviceId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("15. Concurrent gap checks — no duplicate reviews")
    void concurrentGapChecksDoNotDuplicateReviews() throws Exception {
        UUID deviceId = Ids.next();
        insertCursor(deviceId, ENTITY_A, 3);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        insertOutboxEvent(deviceId, 3, ENTITY_A, LOCATION_A);

        GapCheck.DeviceGap gap = new GapCheck.DeviceGap(deviceId, deviceId.toString(), ENTITY_A, 3, 2, 2);
        ScopeContext scope = SystemScope.own(TEST_FEDERATION, null);

        int concurrency = 4;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                tasks.add(() -> {
                    gapCheck.recordDeviceGapReview(gap, scope);
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdown();
        }

        Integer count = superuserJdbc()
                .queryForObject(
                        "SELECT count(*) FROM kernel.audit_event WHERE event_type_code = 'DEVICE_SEQUENCE_GAP' AND subject_id = ?",
                        Integer.class,
                        deviceId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("16. Unauthorized database scope rejected")
    void unauthorizedScopeIsRejected() {
        ScopeContext noneScope = new ScopeContext(
                null, null, null, List.of(), null, PolicyClass.NONE, Set.of(), null, Locale.ENGLISH, null);

        assertThatThrownBy(() -> system.inScope(noneScope, gapCheck::findDeviceGaps))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("device_sequence_gaps requires OWN or FEDERATION_VIEW scope");
    }

    @Test
    @DisplayName("17. App runtime database privileges preserved")
    void appRwCannotDirectlySelectEventOutbox() {
        // App user coop_app (app_rw) has INSERT only on event_outbox
        assertThatThrownBy(() -> jdbc.execute("SELECT count(*) FROM kernel.event_outbox"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");

        // But can execute device_sequence_gaps()
        system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
    }

    @Test
    @DisplayName("18. Large sequence numbers and large reset intervals")
    void largeSequenceNumbersAndResetIntervalsEvaluateEfficiently() {
        UUID deviceId = Ids.next();
        long targetCursor = 1_000_000L;
        insertCursor(deviceId, ENTITY_A, targetCursor);
        insertOutboxEvent(deviceId, 1, ENTITY_A, LOCATION_A);
        // Reset covers 2 to 999,999; missing sequence is 1,000,000
        insertSequenceReset(deviceId, ENTITY_A, 2, targetCursor - 1);

        long start = System.currentTimeMillis();
        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(2000); // Set-based evaluation in milliseconds
        assertThat(gaps).hasSize(1);
        GapCheck.DeviceGap gap = gaps.getFirst();
        assertThat(gap.firstMissing()).isEqualTo(targetCursor);
        assertThat(gap.expectedCount()).isEqualTo(targetCursor);
        assertThat(gap.foundCount()).isEqualTo(targetCursor - 1);
    }

    @Test
    @DisplayName("19. Existing numbering gap-check regression")
    void numberingGapCheckRemainsFullyFunctional() {
        UUID seriesId = Ids.next();
        UUID locationId = Ids.next();

        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'LOCATION', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "RCT",
                        "Receipt");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'RCT', 'LOCATION', ?, ?, ?, 'RCT-REG', 4)",
                        seriesId,
                        TEST_FEDERATION,
                        locationId,
                        Ids.next());

        // Insert documents 1 and 3 (doc 2 missing)
        insertDocument(seriesId, 1L, TEST_FEDERATION, locationId);
        insertDocument(seriesId, 3L, TEST_FEDERATION, locationId);

        List<GapCheck.Gap> numberingGaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();

        assertThat(numberingGaps).hasSize(1);
        assertThat(numberingGaps.getFirst().firstMissing()).isEqualTo(2L);
    }

    @Test
    @DisplayName("20. Historical partition/retention limitations where testable")
    void historicalRetentionLimitationFlagsPrunedRangesAsGapsUnlessReset() {
        UUID deviceId = Ids.next();
        // Cursor indicates sequence has reached 10
        insertCursor(deviceId, ENTITY_A, 10);

        // Suppose outbox rows 1..5 were in an old partition that was pruned/detached;
        // only rows 6..10 remain in outbox.
        for (long s = 6; s <= 10; s++) {
            insertOutboxEvent(deviceId, s, ENTITY_A, LOCATION_A);
        }

        // Without an audited reset or snapshot covering 1..5, the check flags sequence 1 as missing
        List<GapCheck.DeviceGap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().firstMissing()).isEqualTo(1L);

        // If an audited sequence reset is recorded for the archived history 1..5:
        insertSequenceReset(deviceId, ENTITY_A, 1, 5);

        // The gap is now accounted for:
        List<GapCheck.DeviceGap> gapsAfterReset =
                system.inScope(SystemScope.federationView(), gapCheck::findDeviceGaps);
        assertThat(gapsAfterReset).isEmpty();
    }
}
