package lk.coopfed.knoweb.m6pos.internal.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.LocationSessionsClosed;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry;
import lk.coopfed.knoweb.m6pos.api.CashMovementRecorded;
import lk.coopfed.knoweb.m6pos.api.ReceiptRecorded;
import lk.coopfed.knoweb.m6pos.query.PosQueries;
import lk.coopfed.knoweb.m6pos.query.PosQueries.ReceiptFilter;
import lk.coopfed.knoweb.m6pos.query.PosQueries.ReceiptView;
import lk.coopfed.knoweb.m6pos.query.PosQueries.SessionFilter;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ingest handlers' guards (the shape of a fact, never its business) with nothing committed,
 * and what a receipt central finds odd becomes: a flag and a REVIEW record, the receipt kept.
 * Wave 2 (M6-02 to M6-05, M6-08, M6-10; decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md (2), (4)): one case per
 * flag, the replays, the series guard and the paged reads.
 */
class IngestHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e6a1-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("0190e6a1-0000-7000-8000-000000000003");
    private static final UUID SHOP = UUID.fromString("0190e6a1-0000-7000-8000-000000000101");
    private static final UUID DEVICE = UUID.fromString("0190e6a1-0000-7000-8000-000000000301");
    private static final UUID ANOTHER_DEVICE = UUID.fromString("0190e6a1-0000-7000-8000-000000000302");
    /** The device's own RCT series at its position. */
    private static final UUID OWN_SERIES = UUID.fromString("0190e6a1-0000-7000-8000-000000000401");
    /** Another position's series at the same shop, held by another device. */
    private static final UUID FOREIGN_SERIES = UUID.fromString("0190e6a1-0000-7000-8000-000000000402");

    private static final Instant MORNING = Instant.parse("2026-09-28T04:30:00Z");

    @Autowired
    RecordReceiptHandler receipts;

    @Autowired
    RecordSessionHandler sessions;

    @Autowired
    RecordCashMovementHandler cashMovements;

    @Autowired
    PosQueries pos;

    @Autowired
    ConfigRegistry config;

    private long nextNumber = 1;

    @BeforeEach
    void clean() {
        superuserJdbc()
                .execute(
                        "truncate table pos.cash_movement, pos.receipt_tender, pos.receipt_line, pos.receipt, pos.till_session_close,"
                                + " pos.till_session");
        superuserJdbc().update("delete from kernel.numbering_series where owner_entity_id = ?", MPCS);
        superuserJdbc().update("delete from kernel.config_value where key = 'pos.session_variance_review_threshold'");
        if (config instanceof JdbcConfigRegistry jcr) {
            jcr.invalidate("pos.session_variance_review_threshold");
        }
        series(OWN_SERIES, Ids.next(), DEVICE);
        series(FOREIGN_SERIES, Ids.next(), ANOTHER_DEVICE);
        kernel.reset();
    }

    @AfterEach
    void cleanAfterwards() {
        clean();
        superuserJdbc().update("delete from kernel.numbering_series where owner_entity_id = ?", MPCS);
    }

    @Test
    void aReceiptCentralFindsOddIsKeptAndFlagged() {
        UUID elsewhere = Ids.next();
        UUID id = receipts.handle(receipt(Ids.next(), elsewhere, Ids.next()), device());

        assertThat(all()).singleElement().satisfies(r -> {
            assertThat(r.documentId()).isEqualTo(id);
            assertThat(r.locationId()).isEqualTo(SHOP);
            assertThat(r.flags()).containsExactly("LOCATION_MISMATCH", "SESSION_UNKNOWN");
        });
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("RECEIPT_RECORDED", "RECEIPT_FLAGGED");
        assertThat(kernel.committedEvents())
                .filteredOn(ReceiptRecorded.class::isInstance)
                .singleElement()
                .satisfies(e -> assertThat(((ReceiptRecorded) e).flags()).hasSize(2));
    }

    @Test
    void eachTotalDisagreementIsFlaggedBeyondTheToleranceOnly() {
        UUID session = openSession();
        assertThat(flagsOf(sale(session, "100.00", "0.00", "100.00", "100.00", "100.00")))
                .isEmpty();
        assertThat(flagsOf(sale(session, "100.00", "0.00", "100.01", "100.00", "100.01")))
                .as("within one cent for the whole receipt")
                .isEmpty();
        assertThat(flagsOf(sale(session, "90.00", "0.00", "100.00", "100.00", "100.00")))
                .as("gross is not net + tax")
                .containsExactly("TOTAL_MISMATCH");
        assertThat(flagsOf(sale(session, "100.00", "0.00", "100.00", "80.00", "100.00")))
                .as("gross is not the sum of the lines")
                .containsExactly("TOTAL_MISMATCH");
        assertThat(flagsOf(sale(session, "100.00", "0.00", "100.00", "100.00", "90.00")))
                .as("the tenders do not add up to gross")
                .containsExactly("TENDER_MISMATCH");
        assertThat(flagsOf(sale(session, "100.00", "0.00", null, "100.00", "100.00")))
                .as("no gross: nothing to compare")
                .containsExactly("TOTALS_MISSING");
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("RECEIPT_FLAGGED"))
                .hasSize(4);
    }

    @Test
    void aReceiptWithNoSessionOrAfterItsSessionClosedIsFlagged() {
        assertThat(flagsOf(sale(null, "100.00", "0.00", "100.00", "100.00", "100.00")))
                .containsExactly("NO_SESSION");

        UUID session = openSession();
        sessions.handle(close(session, MORNING.plusSeconds(3600), "1000.00"), device());
        assertThat(flagsOf(receipts.handle(
                        receipt(Ids.next(), session, OWN_SERIES, nextNumber++, MORNING.plusSeconds(1800), "100.00"),
                        device())))
                .as("issued before the close")
                .isEmpty();
        assertThat(flagsOf(receipts.handle(
                        receipt(Ids.next(), session, OWN_SERIES, nextNumber++, MORNING.plusSeconds(7200), "100.00"),
                        device())))
                .containsExactly("SESSION_CLOSED");
    }

    @Test
    void aForeignSeriesIsFlaggedAndLeftAlone() {
        UUID session = openSession();
        UUID id = receipts.handle(
                receipt(Ids.next(), session, FOREIGN_SERIES, 900, MORNING.plusSeconds(60), "100.00"), device());

        assertThat(flagsOf(id)).containsExactly("SERIES_FOREIGN");
        assertThat(nextNumberOf(FOREIGN_SERIES)).isEqualTo(1L);
    }

    @Test
    void aNumberFarPastTheSeriesIsFlaggedAndTheSeriesMovesByTheLimitOnly() {
        UUID session = openSession();
        UUID ordinary = receipts.handle(
                receipt(Ids.next(), session, OWN_SERIES, 5, MORNING.plusSeconds(60), "100.00"), device());
        assertThat(flagsOf(ordinary)).isEmpty();
        assertThat(nextNumberOf(OWN_SERIES)).isEqualTo(6L);
        kernel.reset();

        UUID jumped = receipts.handle(
                receipt(Ids.next(), session, OWN_SERIES, 900_000_000_000L, MORNING.plusSeconds(120), "100.00"),
                device());

        assertThat(flagsOf(jumped)).containsExactly("NUMBER_JUMP");
        assertThat(nextNumberOf(OWN_SERIES))
                .as("by pos.series.max_jump (10,000) at most")
                .isEqualTo(10_006L);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("RECEIPT_RECORDED", "RECEIPT_FLAGGED", "RECEIPT_NUMBER_JUMPED");
    }

    @Test
    void aRunawayCounterMovesTheSeriesByTheLimitOnceAndNotWithEveryReceipt() {
        UUID session = openSession();
        receipts.handle(receipt(Ids.next(), session, OWN_SERIES, 5, MORNING.plusSeconds(60), "100.00"), device());
        receipts.handle(
                receipt(Ids.next(), session, OWN_SERIES, 900_000_000_000L, MORNING.plusSeconds(120), "100.00"),
                device());
        assertThat(nextNumberOf(OWN_SERIES)).isEqualTo(10_006L);
        kernel.reset();

        // The till's counter stays wild (TILLM6-08): each receipt is flagged, the series stays.
        UUID again = receipts.handle(
                receipt(Ids.next(), session, OWN_SERIES, 900_000_000_001L, MORNING.plusSeconds(180), "100.00"),
                device());
        UUID andAgain = receipts.handle(
                receipt(Ids.next(), session, OWN_SERIES, 900_000_000_002L, MORNING.plusSeconds(240), "100.00"),
                device());

        assertThat(flagsOf(again)).containsExactly("NUMBER_JUMP");
        assertThat(flagsOf(andAgain)).containsExactly("NUMBER_JUMP");
        assertThat(nextNumberOf(OWN_SERIES)).as("not moved again").isEqualTo(10_006L);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly(
                        "RECEIPT_RECORDED",
                        "RECEIPT_FLAGGED",
                        "RECEIPT_NUMBER_JUMPED",
                        "RECEIPT_RECORDED",
                        "RECEIPT_FLAGGED",
                        "RECEIPT_NUMBER_JUMPED");

        // A number in reach of the series still moves it as before.
        UUID inReach = receipts.handle(
                receipt(Ids.next(), session, OWN_SERIES, 10_006L, MORNING.plusSeconds(300), "100.00"), device());
        assertThat(flagsOf(inReach)).isEmpty();
        assertThat(nextNumberOf(OWN_SERIES)).isEqualTo(10_007L);
    }

    @Test
    void aReplayWithOtherContentKeepsTheFirstAndRaisesAnAlert() {
        UUID session = openSession();
        UUID id = Ids.next();
        receipts.handle(withHash(receipt(id, session, OWN_SERIES, 1, MORNING, "100.00"), "hash-first"), device());
        kernel.reset();

        receipts.handle(withHash(receipt(id, session, OWN_SERIES, 1, MORNING, "100.00"), "hash-first"), device());
        assertThat(kernel.committedAudit())
                .as("the same content again is a redelivery")
                .isEmpty();

        receipts.handle(withHash(receipt(id, session, OWN_SERIES, 1, MORNING, "999.00"), "hash-later"), device());
        assertThat(pos.receipt(id, device()))
                .hasValueSatisfying(r -> assertThat(r.grossAmount()).isEqualByComparingTo("100.00"));
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("RECEIPT_REPLAY_DIFFERS");
            assertThat(String.valueOf(a.after())).contains("hash-first", "hash-later");
        });
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aSecondCloseWithOtherAmountsKeepsTheFirstAndRaisesAnAlert() {
        UUID session = openSession();
        sessions.handle(close(session, MORNING.plusSeconds(3600), "1000.00"), device());
        kernel.reset();

        sessions.handle(close(session, MORNING.plusSeconds(3600), "1000.00"), device());
        assertThat(kernel.committedAudit()).isEmpty();

        sessions.handle(close(session, MORNING.plusSeconds(3700), "800.00"), device());
        assertThat(pos.session(session, device()))
                .hasValueSatisfying(s -> assertThat(s.countedCash()).isEqualByComparingTo("1000.00"));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSE_REPLAY_DIFFERS");
    }

    @Test
    void successfulDigitalTendersWithReferencePersistToPostgresAndQueriesReturnReference() {
        UUID session = openSession();
        UUID id = Ids.next();
        BigDecimal gross = new BigDecimal("1750.00");
        RecordReceipt r = new RecordReceipt(
                id,
                SHOP,
                null,
                DEVICE,
                session,
                OWN_SERIES,
                nextNumber++,
                "T-1",
                MORNING,
                null,
                null,
                "LKR",
                gross,
                BigDecimal.ZERO,
                gross,
                null,
                1L,
                List.of(new RecordReceipt.Line(1, Ids.next(), null, "EA", BigDecimal.ONE, gross, gross)),
                List.of(
                        new RecordReceipt.Tender(1, "CARD", new BigDecimal("1000.00"), "AUTH-CARD-1001"),
                        new RecordReceipt.Tender(2, "WALLET", new BigDecimal("500.00"), "AUTH-WAL-2002"),
                        new RecordReceipt.Tender(3, "QR", new BigDecimal("250.00"), "AUTH-QR-3003")));

        receipts.handle(r, device());

        assertThat(pos.receipt(id, device())).hasValueSatisfying(receipt -> {
            assertThat(receipt.grossAmount()).isEqualByComparingTo("1750.00");
            assertThat(receipt.flags()).isEmpty();
            assertThat(receipt.tenders()).hasSize(3);

            PosQueries.ReceiptView.Tender t1 = receipt.tenders().get(0);
            assertThat(t1.seq()).isEqualTo(1);
            assertThat(t1.kind()).isEqualTo("CARD");
            assertThat(t1.amount()).isEqualByComparingTo("1000.00");
            assertThat(t1.reference()).isEqualTo("AUTH-CARD-1001");

            PosQueries.ReceiptView.Tender t2 = receipt.tenders().get(1);
            assertThat(t2.seq()).isEqualTo(2);
            assertThat(t2.kind()).isEqualTo("WALLET");
            assertThat(t2.amount()).isEqualByComparingTo("500.00");
            assertThat(t2.reference()).isEqualTo("AUTH-WAL-2002");

            PosQueries.ReceiptView.Tender t3 = receipt.tenders().get(2);
            assertThat(t3.seq()).isEqualTo(3);
            assertThat(t3.kind()).isEqualTo("QR");
            assertThat(t3.amount()).isEqualByComparingTo("250.00");
            assertThat(t3.reference()).isEqualTo("AUTH-QR-3003");
        });

        List<String> refs = superuserJdbc()
                .queryForList(
                        "select reference from pos.receipt_tender where document_id = ? order by seq",
                        String.class,
                        id);
        assertThat(refs).containsExactly("AUTH-CARD-1001", "AUTH-WAL-2002", "AUTH-QR-3003");
    }

    @Test
    void missingReferenceOnDigitalTenderIsFlaggedAsTenderReferenceMissing() {
        UUID session = openSession();
        UUID id = Ids.next();
        BigDecimal gross = new BigDecimal("100.00");
        RecordReceipt r = new RecordReceipt(
                id,
                SHOP,
                null,
                DEVICE,
                session,
                OWN_SERIES,
                nextNumber++,
                "T-1",
                MORNING,
                null,
                null,
                "LKR",
                gross,
                BigDecimal.ZERO,
                gross,
                null,
                1L,
                List.of(new RecordReceipt.Line(1, Ids.next(), null, "EA", BigDecimal.ONE, gross, gross)),
                List.of(new RecordReceipt.Tender(1, "CARD", gross, null)));

        receipts.handle(r, device());

        assertThat(pos.receipt(id, device())).hasValueSatisfying(receipt -> {
            assertThat(receipt.flags()).containsExactly("TENDER_REFERENCE_MISSING");
        });
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("RECEIPT_FLAGGED"))
                .singleElement()
                .satisfies(a -> assertThat(String.valueOf(a.after())).contains("TENDER_REFERENCE_MISSING"));
    }

    @Test
    void replayWithDifferentTenderDataKeepsFirstAndAuditsReceiptReplayDiffers() {
        UUID session = openSession();
        UUID id = Ids.next();
        BigDecimal gross = new BigDecimal("100.00");
        RecordReceipt first = new RecordReceipt(
                id,
                SHOP,
                null,
                DEVICE,
                session,
                OWN_SERIES,
                1L,
                "T-1",
                MORNING,
                null,
                null,
                "LKR",
                gross,
                BigDecimal.ZERO,
                gross,
                "same-hash",
                1L,
                List.of(new RecordReceipt.Line(1, Ids.next(), null, "EA", BigDecimal.ONE, gross, gross)),
                List.of(new RecordReceipt.Tender(1, "CARD", gross, "AUTH-ORIGINAL")));

        receipts.handle(first, device());
        kernel.reset();

        // Exact redelivery: no audit
        receipts.handle(first, device());
        assertThat(kernel.committedAudit()).isEmpty();

        // Replay with altered tender reference:
        RecordReceipt altered = new RecordReceipt(
                id,
                SHOP,
                null,
                DEVICE,
                session,
                OWN_SERIES,
                1L,
                "T-1",
                MORNING,
                null,
                null,
                "LKR",
                gross,
                BigDecimal.ZERO,
                gross,
                "same-hash",
                1L,
                List.of(new RecordReceipt.Line(1, Ids.next(), null, "EA", BigDecimal.ONE, gross, gross)),
                List.of(new RecordReceipt.Tender(1, "CARD", gross, "AUTH-TAMPERED")));

        receipts.handle(altered, device());

        // First copy is preserved in DB!
        assertThat(pos.receipt(id, device())).hasValueSatisfying(receipt -> {
            assertThat(receipt.tenders().get(0).reference()).isEqualTo("AUTH-ORIGINAL");
        });

        // Replay differs is recorded
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("RECEIPT_REPLAY_DIFFERS");
            assertThat(String.valueOf(a.after())).contains("tendersDiffer=true");
        });
    }

    @Test
    void receiptsArePagedNewestFirstByDayWithACursorAndAFlaggedFilter() {
        UUID session = openSession();
        List<UUID> today = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            today.add(receipts.handle(
                    receipt(Ids.next(), session, OWN_SERIES, nextNumber++, MORNING.plusSeconds(60L * i), "100.00"),
                    device()));
        }
        UUID odd = receipts.handle(
                receipt(Ids.next(), session, FOREIGN_SERIES, 7, MORNING.plusSeconds(600), "100.00"), device());
        UUID nextDay = receipts.handle(
                receipt(Ids.next(), session, OWN_SERIES, nextNumber++, MORNING.plusSeconds(86_400), "100.00"),
                device());
        LocalDate day = LocalDate.parse("2026-09-28");

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            PosQueries.Page<ReceiptView> page =
                    pos.receipts(new ReceiptFilter(SHOP, day, false, cursor, 2), entityWide());
            assertThat(page.items()).hasSizeLessThanOrEqualTo(2);
            page.items().forEach(r -> seen.add(r.documentId()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen)
                .containsExactly(odd, today.get(4), today.get(3), today.get(2), today.get(1), today.get(0))
                .doesNotContain(nextDay);
        assertThat(pos.receipts(new ReceiptFilter(SHOP, day, true, null, null), entityWide())
                        .items())
                .extracting(ReceiptView::documentId)
                .containsExactly(odd);
        assertThat(pos.receipts(new ReceiptFilter(SHOP, day, false, null, null), entityWide())
                        .items()
                        .getFirst()
                        .lines())
                .hasSize(1);
    }

    @Test
    void aCloseWhoseOpenNeverArrivedIsListed() {
        UUID opened = openSession();
        UUID orphan = Ids.next();
        sessions.handle(close(orphan, MORNING.plusSeconds(7200), "500.00"), device());

        assertThat(pos.sessions(new SessionFilter(SHOP, LocalDate.parse("2026-09-28"), null, null), entityWide())
                        .items())
                .extracting(PosQueries.SessionView::sessionId)
                .containsExactly(orphan, opened);
        assertThat(pos.session(orphan, entityWide())).hasValueSatisfying(s -> {
            assertThat(s.openedAt()).isNull();
            assertThat(s.closedAt()).isNotNull();
            assertThat(s.countedCash()).isEqualByComparingTo("500.00");
        });
    }

    @Test
    void anotherEntityReadsNeitherTheReceiptNorTheSession() {
        UUID session = openSession();
        UUID id = receipts.handle(receipt(Ids.next(), session, OWN_SERIES, 1, MORNING, "100.00"), device());

        assertThat(pos.receipt(id, entityWide())).isPresent();
        assertThat(pos.receipt(id, ScopeContext.dev(UUID.randomUUID(), OTHER, null)))
                .isEmpty();
        assertThat(pos.session(session, ScopeContext.dev(UUID.randomUUID(), OTHER, null)))
                .isEmpty();
        assertThat(pos.receipts(
                                new ReceiptFilter(SHOP, null, false, null, null),
                                ScopeContext.dev(UUID.randomUUID(), OTHER, null))
                        .items())
                .isEmpty();
    }

    @Test
    void theGuardsRefuseAMalformedFactAndCommitNothing() {
        assertProblem(() -> receipts.handle(receipt(Ids.next(), SHOP, null), entityWide()), "m6.scope.device_required");
        assertProblem(() -> receipts.handle(receipt(null, SHOP, null), device()), "m6.receipt.malformed");
        assertProblem(
                () -> sessions.handle(
                        new RecordSession(null, false, null, null, null, Instant.now(), null, null, null, null),
                        device()),
                "m6.session.malformed");
        assertProblem(
                () -> sessions.handle(
                        new RecordSession(Ids.next(), false, null, null, null, Instant.now(), null, null, null, null),
                        entityWide()),
                "m6.scope.device_required");
        assertProblem(
                () -> pos.receipts(new ReceiptFilter(SHOP, null, false, "not a cursor", null), entityWide()),
                "request.malformed");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(all()).isEmpty();
    }

    // ---- helpers --------------------------------------------------------------------------

    private void series(UUID seriesId, UUID position, UUID holder) {
        superuserJdbc()
                .update(
                        """
                        insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id,
                                                             location_id, till_position_id, prefix, holder_device_id)
                        values (?, 'RCT', 'TILL_POSITION', ?, ?, ?, 'M6T', ?)
                        """,
                        seriesId,
                        MPCS,
                        SHOP,
                        position,
                        holder);
    }

    private Long nextNumberOf(UUID seriesId) {
        return superuserJdbc()
                .queryForObject(
                        "select next_number from kernel.numbering_series where series_id = ?", Long.class, seriesId);
    }

    private UUID openSession() {
        return openSession(DEVICE, null);
    }

    private UUID openSession(UUID deviceId) {
        return openSession(deviceId, null);
    }

    private UUID openSession(UUID deviceId, LocalDate businessDate) {
        UUID session = Ids.next();
        sessions.handle(
                new RecordSession(
                        session,
                        false,
                        null,
                        null,
                        businessDate,
                        MORNING.minusSeconds(600),
                        BigDecimal.ZERO,
                        null,
                        null,
                        null),
                device(deviceId));
        return session;
    }

    private static RecordSession close(UUID session, Instant at, String counted) {
        return close(session, null, at, counted);
    }

    private static RecordSession close(UUID session, LocalDate businessDate, Instant at, String counted) {
        return new RecordSession(
                session,
                true,
                null,
                null,
                businessDate,
                at,
                null,
                new BigDecimal(counted),
                new BigDecimal("1000.00"),
                new BigDecimal(counted).subtract(new BigDecimal("1000.00")));
    }

    private UUID sale(UUID session, String net, String tax, String gross, String lineTotal, String tender) {
        RecordReceipt r = new RecordReceipt(
                Ids.next(),
                SHOP,
                null,
                DEVICE,
                session,
                OWN_SERIES,
                nextNumber++,
                "T-" + nextNumber,
                MORNING,
                null,
                null,
                "LKR",
                new BigDecimal(net),
                new BigDecimal(tax),
                gross == null ? null : new BigDecimal(gross),
                null,
                nextNumber,
                List.of(new RecordReceipt.Line(
                        1,
                        Ids.next(),
                        null,
                        "EA",
                        BigDecimal.ONE,
                        new BigDecimal(lineTotal),
                        new BigDecimal(lineTotal))),
                List.of(new RecordReceipt.Tender(1, "CASH", new BigDecimal(tender))));
        return receipts.handle(r, device());
    }

    private List<String> flagsOf(UUID documentId) {
        return pos.receipt(documentId, device()).orElseThrow().flags();
    }

    private List<ReceiptView> all() {
        return pos.receipts(new ReceiptFilter(SHOP, null, false, null, null), device())
                .items();
    }

    private static RecordReceipt receipt(UUID documentId, UUID location, UUID session) {
        return new RecordReceipt(
                documentId,
                location,
                null,
                DEVICE,
                session,
                OWN_SERIES,
                1L,
                "T-1",
                Instant.now(),
                null,
                null,
                "LKR",
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                null,
                1L,
                List.of(new RecordReceipt.Line(
                        1, Ids.next(), null, "EA", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100.00"))),
                List.of(new RecordReceipt.Tender(1, "CASH", new BigDecimal("100.00"))));
    }

    /** A receipt in agreement with itself (net = gross = line = tender), of this series and number. */
    private static RecordReceipt receipt(
            UUID documentId, UUID session, UUID series, long number, Instant issuedAt, String amount) {
        BigDecimal money = new BigDecimal(amount);
        return new RecordReceipt(
                documentId,
                SHOP,
                null,
                DEVICE,
                session,
                series,
                number,
                "T-" + number,
                issuedAt,
                null,
                null,
                "LKR",
                money,
                BigDecimal.ZERO,
                money,
                null,
                number,
                List.of(new RecordReceipt.Line(1, Ids.next(), null, "EA", BigDecimal.ONE, money, money)),
                List.of(new RecordReceipt.Tender(1, "CASH", money)));
    }

    private static RecordReceipt withHash(RecordReceipt r, String hash) {
        return new RecordReceipt(
                r.documentId(),
                r.locationId(),
                r.tillPositionId(),
                r.deviceId(),
                r.sessionId(),
                r.seriesId(),
                r.docNumber(),
                r.docNumberDisplay(),
                r.issuedAt(),
                r.businessDate(),
                r.operatorUserId(),
                r.currency(),
                r.netAmount(),
                r.taxAmount(),
                r.grossAmount(),
                hash,
                r.deviceSeq(),
                r.lines(),
                r.tenders());
    }

    /** The scope the consumer framework gives a till's fact: the device's entity at its shop. */
    private static ScopeContext device() {
        return device(DEVICE);
    }

    private static ScopeContext device(UUID deviceId) {
        Scope scope = new Scope(MPCS, SHOP);
        return new ScopeContext(
                null, deviceId, MPCS, List.of(scope), scope, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private static ScopeContext entityWide() {
        return ScopeContext.dev(UUID.randomUUID(), MPCS, null);
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String id) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(id));
    }

    // ---- cash movement tests (TILL-SELL-10) ------------------------------------------------

    @Test
    void aCashMovementRecordedAndAudited() {
        UUID session = openSession();
        kernel.reset();

        UUID movementId = Ids.next();
        UUID id = cashMovements.handle(
                cashMovement(movementId, session, "DROP", new BigDecimal("500.00"), "midday sweep"), device());

        assertThat(id).isEqualTo(movementId);
        List<PosQueries.CashMovementView> list = pos.cashMovements(session, device());
        assertThat(list).singleElement().satisfies(m -> {
            assertThat(m.movementId()).isEqualTo(movementId);
            assertThat(m.sessionId()).isEqualTo(session);
            assertThat(m.locationId()).isEqualTo(SHOP);
            assertThat(m.kind()).isEqualTo("DROP");
            assertThat(m.amount()).isEqualByComparingTo("500.00");
            assertThat(m.reason()).isEqualTo("midday sweep");
            assertThat(m.flags()).isEmpty();
        });

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("CASH_MOVEMENT_RECORDED");
        assertThat(kernel.committedEvents())
                .filteredOn(CashMovementRecorded.class::isInstance)
                .singleElement()
                .satisfies(e -> {
                    CashMovementRecorded ev = (CashMovementRecorded) e;
                    assertThat(ev.movementId()).isEqualTo(movementId);
                    assertThat(ev.kind()).isEqualTo("DROP");
                    assertThat(ev.amount()).isEqualByComparingTo("500.00");
                });
    }

    @Test
    void aCashMovementCentralFindsOddIsKeptAndFlagged() {
        UUID unknownSession = Ids.next();
        UUID movementId = Ids.next();
        UUID elsewhere = Ids.next();

        RecordCashMovement strange = new RecordCashMovement(
                movementId,
                unknownSession,
                elsewhere,
                null,
                DEVICE,
                "PAYOUT",
                new BigDecimal("120.00"),
                MORNING,
                Ids.next(),
                "emergency milk",
                1L);

        UUID id = cashMovements.handle(strange, device());
        assertThat(id).isEqualTo(movementId);

        List<PosQueries.CashMovementView> list = pos.cashMovements(unknownSession, device());
        assertThat(list).singleElement().satisfies(m -> {
            assertThat(m.movementId()).isEqualTo(movementId);
            assertThat(m.flags()).containsExactly("LOCATION_MISMATCH", "SESSION_UNKNOWN");
        });

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("CASH_MOVEMENT_FLAGGED");
    }

    @Test
    void aCashMovementReplayedWithSameDetailsIsNoOp() {
        UUID session = openSession();
        kernel.reset();

        UUID movementId = Ids.next();
        RecordCashMovement command =
                cashMovement(movementId, session, "DROP", new BigDecimal("300.00"), "routine drop");

        cashMovements.handle(command, device());
        assertThat(kernel.committedAudit()).hasSize(1);
        kernel.reset();

        // Exact replay
        UUID replayed = cashMovements.handle(command, device());
        assertThat(replayed).isEqualTo(movementId);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aCashMovementReplayedWithDifferentDetailsAuditsReplayDiffersWithoutModifyingFirst() {
        UUID session = openSession();
        kernel.reset();

        UUID movementId = Ids.next();
        RecordCashMovement first = cashMovement(movementId, session, "DROP", new BigDecimal("300.00"), "routine drop");
        cashMovements.handle(first, device());
        kernel.reset();

        // Arrives again with different amount
        RecordCashMovement altered =
                cashMovement(movementId, session, "DROP", new BigDecimal("600.00"), "routine drop");
        cashMovements.handle(altered, device());

        // First copy is kept
        List<PosQueries.CashMovementView> list = pos.cashMovements(session, device());
        assertThat(list).singleElement().satisfies(m -> {
            assertThat(m.amount()).isEqualByComparingTo("300.00");
        });

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("CASH_MOVEMENT_REPLAY_DIFFERS");
    }

    @Test
    void aCashMovementGuardsInvalidAmountAndEmptyReason() {
        UUID session = openSession();
        UUID movementId = Ids.next();

        assertProblem(
                () -> cashMovements.handle(
                        cashMovement(movementId, session, "DROP", BigDecimal.ZERO, "zero amount"), device()),
                "m6.cash_movement.invalid_amount");

        assertProblem(
                () -> cashMovements.handle(
                        cashMovement(movementId, session, "DROP", new BigDecimal("-10.00"), "negative amount"),
                        device()),
                "m6.cash_movement.invalid_amount");

        assertProblem(
                () -> cashMovements.handle(
                        cashMovement(movementId, session, "DROP", new BigDecimal("100.00"), "   "), device()),
                "m6.cash_movement.reason_required");

        assertProblem(
                () -> cashMovements.handle(
                        new RecordCashMovement(
                                null,
                                session,
                                SHOP,
                                null,
                                DEVICE,
                                "DROP",
                                new BigDecimal("100.00"),
                                MORNING,
                                Ids.next(),
                                "reason",
                                1L),
                        device()),
                "m6.cash_movement.malformed");

        assertProblem(
                () -> cashMovements.handle(
                        cashMovement(movementId, session, "DROP", new BigDecimal("100.00"), "reason"), entityWide()),
                "m6.scope.device_required");
    }

    // ---- session shortage review tests (TILL-SELL-11) ---------------------------------------

    @Test
    void sessionCloseShortageBelowThresholdAuditsOnlyClosed() {
        UUID session = openSession();
        kernel.reset();
        // Counted 600, expected 1000 => variance -400, shortage 400 <= default threshold 500
        sessions.handle(close(session, MORNING.plusSeconds(3600), "600.00"), device());

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED");
    }

    @Test
    void sessionCloseShortageAtExactThresholdBoundaryAuditsOnlyClosed() {
        UUID session = openSession();
        kernel.reset();
        // Counted 500, expected 1000 => variance -500, shortage 500 == default threshold 500 (not strictly exceeded)
        sessions.handle(close(session, MORNING.plusSeconds(3600), "500.00"), device());

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED");
    }

    @Test
    void sessionCloseZeroVarianceAuditsOnlyClosed() {
        UUID session = openSession();
        kernel.reset();
        // Counted 1000, expected 1000 => variance 0, zero shortage
        sessions.handle(close(session, MORNING.plusSeconds(3600), "1000.00"), device());

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED");
    }

    @Test
    void sessionCloseSurplusAuditsOnlyClosed() {
        UUID session = openSession();
        kernel.reset();
        // Counted 1250, expected 1000 => variance +250 (surplus, not a shortage)
        sessions.handle(close(session, MORNING.plusSeconds(3600), "1250.00"), device());

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED");
    }

    @Test
    void sessionCloseShortageExceedingThresholdRaisesReviewAudit() {
        UUID session = openSession();
        kernel.reset();
        // Counted 400, expected 1000 => variance -600, shortage 600 > default threshold 500
        sessions.handle(close(session, MORNING.plusSeconds(3600), "400.00"), device());

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED", "TILL_SESSION_SHORTAGE_REVIEW");

        KernelRecorder.AuditRecord review = kernel.committedAudit().stream()
                .filter(a -> a.eventType().equals("TILL_SESSION_SHORTAGE_REVIEW"))
                .findFirst()
                .orElseThrow();
        assertThat(review.subject().id()).isEqualTo(session);
        assertThat(review.subject().type()).isEqualTo("till_session");
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) review.after();
        assertThat(after.get("messageId")).isEqualTo("m6.session.shortage_review");
        assertThat(after.get("sessionId")).isEqualTo(session);
        assertThat((BigDecimal) after.get("countedCash")).isEqualByComparingTo("400.00");
        assertThat((BigDecimal) after.get("expectedCash")).isEqualByComparingTo("1000.00");
        assertThat((BigDecimal) after.get("variance")).isEqualByComparingTo("-600.00");
        assertThat((BigDecimal) after.get("shortage")).isEqualByComparingTo("600.00");
        assertThat((BigDecimal) after.get("threshold")).isEqualByComparingTo("500.00");
        assertThat(after.get("locationId")).isEqualTo(SHOP);
        assertThat(after.get("deviceId")).isEqualTo(DEVICE);
        assertThat(review.reason()).isEqualTo("Cash shortage of 600.00 exceeds review threshold 500.00");
    }

    @Test
    void sessionCloseReviewIsIdempotentOnIdenticalReplay() {
        UUID session = openSession();
        kernel.reset();
        // First close with shortage exceeding threshold
        sessions.handle(close(session, MORNING.plusSeconds(3600), "400.00"), device());
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED", "TILL_SESSION_SHORTAGE_REVIEW");
        kernel.reset();

        // Identical replay: same amounts
        sessions.handle(close(session, MORNING.plusSeconds(3600), "400.00"), device());
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void sessionCloseReviewDivergentReplayRaisesAlertAndNoDuplicateReview() {
        UUID session = openSession();
        kernel.reset();
        // First close with shortage exceeding threshold
        sessions.handle(close(session, MORNING.plusSeconds(3600), "400.00"), device());
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED", "TILL_SESSION_SHORTAGE_REVIEW");
        kernel.reset();

        // Divergent replay with different amounts
        sessions.handle(close(session, MORNING.plusSeconds(3700), "350.00"), device());
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSE_REPLAY_DIFFERS");
    }

    @Test
    void sessionCloseShortageRespectsEntityConfiguredThresholdOverride() {
        // Entity configures threshold to 100.00
        superuserJdbc()
                .update(
                        "insert into kernel.config_value (key, scope_entity_id, value, reason) values ('pos.session_variance_review_threshold', ?, '100.00'::jsonb, 'test')",
                        MPCS);
        if (config instanceof JdbcConfigRegistry jcr) {
            jcr.invalidate("pos.session_variance_review_threshold");
        }

        UUID session = openSession();
        kernel.reset();
        // Counted 850, expected 1000 => variance -150, shortage 150
        // 150 <= default 500.00, but 150 > entity override 100.00
        sessions.handle(close(session, MORNING.plusSeconds(3600), "850.00"), device());

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED", "TILL_SESSION_SHORTAGE_REVIEW");
        KernelRecorder.AuditRecord review = kernel.committedAudit().stream()
                .filter(a -> a.eventType().equals("TILL_SESSION_SHORTAGE_REVIEW"))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) review.after();
        assertThat((BigDecimal) after.get("threshold")).isEqualByComparingTo("100.00");
    }

    @Test
    void sessionCloseHandlesInvalidNegativeThresholdSafelyWithoutAbsMasking() {
        // Entity configures invalid negative threshold: -100.00
        // Handler must not use .abs() to mask this as 100.00; it falls back to default 500.00
        superuserJdbc()
                .update(
                        "insert into kernel.config_value (key, scope_entity_id, value, reason) values ('pos.session_variance_review_threshold', ?, '-100.00'::jsonb, 'test')",
                        MPCS);
        if (config instanceof JdbcConfigRegistry jcr) {
            jcr.invalidate("pos.session_variance_review_threshold");
        }

        UUID session = openSession();
        kernel.reset();
        // Shortage 250: would exceed 100 if abs() was used, but within fallback default 500
        sessions.handle(close(session, MORNING.plusSeconds(3600), "750.00"), device());

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED");
    }

    @Test
    void sessionCloseWithMissingAmountsDoesNotThrowOrRaiseReview() {
        UUID session = openSession();
        kernel.reset();
        // Close with null amounts
        RecordSession close =
                new RecordSession(session, true, null, null, null, MORNING.plusSeconds(3600), null, null, null, null);
        sessions.handle(close, device());

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TILL_SESSION_CLOSED");
    }

    // ---- automatic day close tests (TILL-SELL-12) -------------------------------------------

    @Test
    void singleTillClosingLastSessionPublishesLocationSessionsClosed() {
        UUID session = openSession();
        kernel.reset();

        sessions.handle(close(session, MORNING.plusSeconds(3600), "1000.00"), device());

        assertThat(kernel.committedEvents())
                .filteredOn(LocationSessionsClosed.class::isInstance)
                .singleElement()
                .satisfies(e -> {
                    LocationSessionsClosed closed = (LocationSessionsClosed) e;
                    assertThat(closed.closedLocationId()).isEqualTo(SHOP);
                    assertThat(closed.ownerEntityId()).isEqualTo(MPCS);
                    assertThat(closed.businessDate()).isNotNull();
                });
    }

    @Test
    void multiTillClosingInSequenceOnlyPublishesLocationSessionsClosedWhenAllClosed() {
        UUID session1 = openSession(DEVICE);
        UUID session2 = openSession(ANOTHER_DEVICE);
        kernel.reset();

        // First till closes: session2 is still open -> no LocationSessionsClosed
        sessions.handle(close(session1, MORNING.plusSeconds(3600), "1000.00"), device(DEVICE));
        assertThat(kernel.committedEvents())
                .filteredOn(LocationSessionsClosed.class::isInstance)
                .isEmpty();

        // Second till closes: 0 sessions remaining -> LocationSessionsClosed published
        kernel.reset();
        sessions.handle(close(session2, MORNING.plusSeconds(3700), "1000.00"), device(ANOTHER_DEVICE));
        assertThat(kernel.committedEvents())
                .filteredOn(LocationSessionsClosed.class::isInstance)
                .singleElement()
                .satisfies(e -> {
                    LocationSessionsClosed closed = (LocationSessionsClosed) e;
                    assertThat(closed.closedLocationId()).isEqualTo(SHOP);
                    assertThat(closed.ownerEntityId()).isEqualTo(MPCS);
                });
    }

    @Test
    void delayedPreviousDaySessionCloseDoesNotPublishLocationSessionsClosed() {
        LocalDate currentTradingDate = LocalDate.now();
        LocalDate yesterday = currentTradingDate.minusDays(1);
        UUID session = openSession(DEVICE, yesterday);
        kernel.reset();

        // Till syncs delayed close from yesterday
        sessions.handle(close(session, yesterday, MORNING.plusSeconds(3600), "1000.00"), device());

        assertThat(kernel.committedEvents())
                .filteredOn(LocationSessionsClosed.class::isInstance)
                .isEmpty();
    }

    @Test
    void duplicateSessionCloseReplayDoesNotPublishDuplicateLocationSessionsClosed() {
        UUID session = openSession();
        kernel.reset();

        RecordSession closeCommand = close(session, MORNING.plusSeconds(3600), "1000.00");
        sessions.handle(closeCommand, device());
        assertThat(kernel.committedEvents())
                .filteredOn(LocationSessionsClosed.class::isInstance)
                .hasSize(1);

        kernel.reset();
        // Duplicate delivery / replay
        sessions.handle(closeCommand, device());
        assertThat(kernel.committedEvents())
                .filteredOn(LocationSessionsClosed.class::isInstance)
                .isEmpty();
    }

    @Test
    void concurrentSessionClosesSerializeSafelyAndPublishExactlyOneLocationSessionsClosed() throws Exception {
        UUID session1 = openSession(DEVICE);
        UUID session2 = openSession(ANOTHER_DEVICE);
        kernel.reset();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Void> task1 = () -> {
            barrier.await();
            sessions.handle(close(session1, MORNING.plusSeconds(3600), "1000.00"), device(DEVICE));
            return null;
        };
        Callable<Void> task2 = () -> {
            barrier.await();
            sessions.handle(close(session2, MORNING.plusSeconds(3600), "1000.00"), device(ANOTHER_DEVICE));
            return null;
        };

        Future<Void> f1 = pool.submit(task1);
        Future<Void> f2 = pool.submit(task2);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Exactly one LocationSessionsClosed event must be published across both concurrent closes
        assertThat(kernel.committedEvents())
                .filteredOn(LocationSessionsClosed.class::isInstance)
                .hasSize(1);
    }

    private RecordCashMovement cashMovement(
            UUID movementId, UUID session, String kind, BigDecimal amount, String reason) {
        return new RecordCashMovement(
                movementId, session, SHOP, null, DEVICE, kind, amount, MORNING, Ids.next(), reason, 1L);
    }
}
