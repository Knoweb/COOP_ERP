package lk.coopfed.knoweb.kernel.internal.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class GapCheckQuarantineIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GapCheck gapCheck;

    @Autowired
    private SystemScope system;

    @Test
    void missingNumberFullyExplainedByQuarantineProducesNoGap() {
        UUID deviceId = Ids.next();
        UUID seriesId = Ids.next();
        UUID locationId = Ids.next();

        // 1. Setup series
        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'LOCATION', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "RCT",
                        "Receipt");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'RCT', 'LOCATION', ?, ?, ?, 'RCT-TEST', 5)",
                        seriesId,
                        TEST_FEDERATION,
                        locationId,
                        deviceId);

        // 2. Insert documents 1, 2, 4 (3 is missing)
        insertDocument(seriesId, 1L, TEST_FEDERATION, locationId, deviceId);
        insertDocument(seriesId, 2L, TEST_FEDERATION, locationId, deviceId);
        insertDocument(seriesId, 4L, TEST_FEDERATION, locationId, deviceId);

        // 3. Document 3 is missing. Without quarantine, it's a gap
        List<GapCheck.Gap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().firstMissing()).isEqualTo(3L);

        // 4. Insert quarantine record for doc 3 (SCHEMA error, e.g. from lost-data reset attempt)
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, location_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number) "
                                + "values (?, ?, ?, ?, ?, 1, ?, 'receipt.issued.v1', 'SCHEMA', '{}', ?, ?)",
                        Ids.next(),
                        deviceId,
                        TEST_FEDERATION,
                        locationId,
                        Ids.next(),
                        Ids.next(),
                        seriesId,
                        3L);

        // 5. Gap is fully explained, no gaps reported
        gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();
        assertThat(gaps).isEmpty();
    }

    @Test
    void unrelatedOrMaliciousQuarantineDoesNotSuppressGenuineGap() {
        UUID deviceIdA = Ids.next();
        UUID deviceIdB = Ids.next();
        UUID seriesId = Ids.next();

        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'LOCATION', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "RCT",
                        "Receipt");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'RCT', 'LOCATION', ?, ?, ?, 'RCT-TEST', 3)",
                        seriesId,
                        TEST_FEDERATION,
                        Ids.next(),
                        deviceIdA);

        insertDocument(seriesId, 1L, TEST_FEDERATION, null, deviceIdA);
        // Doc 2 is missing.

        // Malicious Device B attempts to quarantine doc 2 for Device A's series
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number) "
                                + "values (?, ?, ?, ?, 1, ?, 'receipt.issued.v1', 'SCHEMA', '{}', ?, ?)",
                        Ids.next(),
                        deviceIdB,
                        TEST_FEDERATION,
                        Ids.next(),
                        Ids.next(),
                        seriesId,
                        2L);

        // Gap should still be reported because device_id does not match holder_device_id
        List<GapCheck.Gap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().firstMissing()).isEqualTo(2L);
    }

    @Test
    void repairedQuarantineDoesNotSuppressGenuineGap() {
        UUID deviceId = Ids.next();
        UUID seriesId = Ids.next();

        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'LOCATION', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "RCT",
                        "Receipt");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'RCT', 'LOCATION', ?, ?, ?, 'RCT-TEST', 3)",
                        seriesId,
                        TEST_FEDERATION,
                        Ids.next(),
                        deviceId);

        insertDocument(seriesId, 1L, TEST_FEDERATION, null, deviceId);

        // Quarantine doc 2, but marked as REPAIRED (meaning we expect it to be re-sent successfully)
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number, resolved_at, resolution, resolution_reason) "
                                + "values (?, ?, ?, ?, 1, ?, 'receipt.issued.v1', 'SCHEMA', '{}', ?, ?, now(), 'REPAIRED', 'Will resend')",
                        Ids.next(),
                        deviceId,
                        TEST_FEDERATION,
                        Ids.next(),
                        Ids.next(),
                        seriesId,
                        2L);

        // Since it's REPAIRED but still missing from kernel.document, it remains a gap
        List<GapCheck.Gap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().firstMissing()).isEqualTo(2L);
    }

    @Test
    void mixedGaps_quarantinedGapIsSkippedAndGenuineGapIsReported() {
        UUID deviceId = Ids.next();
        UUID seriesId = Ids.next();

        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'LOCATION', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "RCT",
                        "Receipt");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'RCT', 'LOCATION', ?, ?, ?, 'RCT-TEST', 5)",
                        seriesId,
                        TEST_FEDERATION,
                        Ids.next(),
                        deviceId);

        insertDocument(seriesId, 1L, TEST_FEDERATION, null, deviceId);
        insertDocument(seriesId, 4L, TEST_FEDERATION, null, deviceId);
        // Docs 2 and 3 are missing.

        // Quarantine ONLY doc 2. Doc 3 is genuinely missing.
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number) "
                                + "values (?, ?, ?, ?, 1, ?, 'receipt.issued.v1', 'SCHEMA', '{}', ?, ?)",
                        Ids.next(),
                        deviceId,
                        TEST_FEDERATION,
                        Ids.next(),
                        Ids.next(),
                        seriesId,
                        2L);

        List<GapCheck.Gap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().firstMissing()).isEqualTo(3L);
    }

    @Test
    void nullHolderDevice_unrelatedQuarantineDoesNotSuppressGap() {
        UUID deviceId = Ids.next();
        UUID seriesId = Ids.next();

        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'ENTITY', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "ORD",
                        "Order");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'ORD', 'ENTITY', ?, NULL, NULL, 'ORD-TEST', 3)",
                        seriesId,
                        TEST_FEDERATION);

        insertDocument(seriesId, 1L, TEST_FEDERATION, null, null);

        // Quarantine doc 2 by a device. Since holder_device_id is NULL, the quarantine MUST NOT suppress it.
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number) "
                                + "values (?, ?, ?, ?, 1, ?, 'receipt.issued.v1', 'SCHEMA', '{}', ?, ?)",
                        Ids.next(),
                        deviceId,
                        TEST_FEDERATION,
                        Ids.next(),
                        Ids.next(),
                        seriesId,
                        2L);

        List<GapCheck.Gap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().firstMissing()).isEqualTo(2L);
    }

    @Test
    void unresolvedAndDiscardedQuarantines_suppressGap() {
        UUID deviceId = Ids.next();
        UUID seriesId = Ids.next();

        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'LOCATION', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "RCT",
                        "Receipt");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'RCT', 'LOCATION', ?, ?, ?, 'RCT-TEST', 4)",
                        seriesId,
                        TEST_FEDERATION,
                        Ids.next(),
                        deviceId);

        insertDocument(seriesId, 1L, TEST_FEDERATION, null, deviceId);

        // Doc 2 is Unresolved.
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number) "
                                + "values (?, ?, ?, ?, 1, ?, 'receipt.issued.v1', 'SCHEMA', '{}', ?, ?)",
                        Ids.next(),
                        deviceId,
                        TEST_FEDERATION,
                        Ids.next(),
                        Ids.next(),
                        seriesId,
                        2L);

        // Doc 3 is DISCARDED.
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number, resolved_at, resolution, resolution_reason) "
                                + "values (?, ?, ?, ?, 2, ?, 'receipt.issued.v1', 'SCHEMA', '{}', ?, ?, now(), 'DISCARDED', 'Junk')",
                        Ids.next(),
                        deviceId,
                        TEST_FEDERATION,
                        Ids.next(),
                        Ids.next(),
                        seriesId,
                        3L);

        List<GapCheck.Gap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();
        assertThat(gaps).isEmpty();
    }

    @Test
    void rawEventRetention_verifiedEvidenceSurvivesNullRawEvent() {
        UUID deviceId = Ids.next();
        UUID seriesId = Ids.next();

        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'LOCATION', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "RCT",
                        "Receipt");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'RCT', 'LOCATION', ?, ?, ?, 'RCT-TEST', 3)",
                        seriesId,
                        TEST_FEDERATION,
                        Ids.next(),
                        deviceId);

        insertDocument(seriesId, 1L, TEST_FEDERATION, null, deviceId);

        // Quarantine doc 2, resolved as DISCARDED, raw_event is null (data retention cycle completed).
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number, resolved_at, resolution, resolution_reason) "
                                + "values (?, ?, ?, ?, 1, ?, 'receipt.issued.v1', 'SCHEMA', NULL, ?, ?, now(), 'DISCARDED', 'Cleaned up')",
                        Ids.next(),
                        deviceId,
                        TEST_FEDERATION,
                        Ids.next(),
                        Ids.next(),
                        seriesId,
                        2L);

        List<GapCheck.Gap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();
        assertThat(gaps).isEmpty();
    }

    private void insertDocument(UUID seriesId, long docNumber, UUID entityId, UUID locationId, UUID deviceId) {
        superuserJdbc()
                .update(
                        "insert into kernel.document (document_id, doc_type_code, series_id, doc_number, doc_number_display, owner_entity_id, location_id, device_id, status, issued_at, business_date, content_hash) "
                                + "values (?, 'RCT', ?, ?, ?, ?, ?, ?, 'ISSUED', now(), current_date, 'hash')",
                        Ids.next(),
                        seriesId,
                        docNumber,
                        String.valueOf(docNumber),
                        entityId,
                        locationId,
                        deviceId);
    }

    @Test
    void sameDeviceWrongEventTypeQuarantineDoesNotSuppressGenuineGap() {
        UUID deviceId = Ids.next();
        UUID seriesId = Ids.next();

        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'LOCATION', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "RCT",
                        "Receipt");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'RCT', 'LOCATION', ?, ?, ?, 'RCT-TEST', 3)",
                        seriesId,
                        TEST_FEDERATION,
                        Ids.next(),
                        deviceId);

        insertDocument(seriesId, 1L, TEST_FEDERATION, null, deviceId);
        // Doc 2 is missing.

        // The AUTHORIZED device attempts to quarantine doc 2 for the RCT series, but using an UNRELATED event type
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number) "
                                + "values (?, ?, ?, ?, 1, ?, 'stock.counted.v1', 'SCHEMA', '{}', ?, ?)",
                        Ids.next(),
                        deviceId,
                        TEST_FEDERATION,
                        Ids.next(),
                        Ids.next(),
                        seriesId,
                        2L);

        // Does it suppress the gap?
        List<GapCheck.Gap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();

        // We expect the gap to still be reported because 'stock.counted.v1' cannot issue 'RCT'
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().firstMissing()).isEqualTo(2L);
    }

    @Test
    void preciseEventPrefixMatchingValidation() {
        UUID deviceId = Ids.next();
        UUID seriesId = Ids.next();

        superuserJdbc()
                .update(
                        "insert into kernel.document_type (doc_type_code, name_en, series_scope, issuer_role, owning_module) values (?, ?, 'LOCATION', 'SELLER', 'm6pos') on conflict (doc_type_code) do nothing",
                        "RCT",
                        "Receipt");

        superuserJdbc()
                .update(
                        "insert into kernel.document_type_sync_event (doc_type_code, event_prefix) values ('RCT', 'receipt.issued') on conflict do nothing");

        superuserJdbc()
                .update(
                        "insert into kernel.document_type_sync_event (doc_type_code, event_prefix) values ('RCT', 'receipt_test.issued') on conflict do nothing");

        superuserJdbc()
                .update(
                        "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id, holder_device_id, prefix, next_number) "
                                + "values (?, 'RCT', 'LOCATION', ?, ?, ?, 'RCT-TEST2', 10)",
                        seriesId,
                        TEST_FEDERATION,
                        Ids.next(),
                        deviceId);

        insertDocument(seriesId, 1L, TEST_FEDERATION, null, deviceId);

        // Doc 2: matches exact receipt.issued.v1
        insertQuarantine(seriesId, 2L, deviceId, "receipt.issued.v1");
        // Doc 3: matches exact receipt.issued.v2
        insertQuarantine(seriesId, 3L, deviceId, "receipt.issued.v2");
        // Doc 4: does NOT match because of X
        insertQuarantine(seriesId, 4L, deviceId, "receiptXissued.v1");
        // Doc 5: does NOT match because of extra word
        insertQuarantine(seriesId, 5L, deviceId, "receipt.issuedExtra.v1");
        // Doc 6: does NOT match because of extra suffix
        insertQuarantine(seriesId, 6L, deviceId, "receipt.issued.v1.extra");
        // Doc 7: matches exact underscore prefix receipt_test.issued.v1
        insertQuarantine(seriesId, 7L, deviceId, "receipt_test.issued.v1");
        // Doc 8: does NOT match because _ is not a wildcard, so X fails
        insertQuarantine(seriesId, 8L, deviceId, "receiptXtest.issued.v1");

        List<GapCheck.Gap> gaps = system.inScope(SystemScope.federationView(), gapCheck::findGaps).stream()
                .filter(g -> g.seriesId().equals(seriesId))
                .toList();

        // 2, 3, 7 should be suppressed. 4, 5, 6, 8 should NOT be suppressed.
        // Therefore, the first missing is 4.
        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().firstMissing()).isEqualTo(4L);
    }

    private void insertQuarantine(UUID seriesId, long docNumber, UUID deviceId, String eventType) {
        superuserJdbc()
                .update(
                        "insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, batch_id, device_seq, event_id, event_type, reason, raw_event, series_id, doc_number) "
                                + "values (?, ?, ?, ?, 1, ?, ?, 'SCHEMA', '{}', ?, ?)",
                        Ids.next(),
                        deviceId,
                        TEST_FEDERATION,
                        Ids.next(),
                        Ids.next(),
                        eventType,
                        seriesId,
                        docNumber);
    }
}
