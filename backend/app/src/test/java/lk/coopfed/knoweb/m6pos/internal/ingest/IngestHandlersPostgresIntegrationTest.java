package lk.coopfed.knoweb.m6pos.internal.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
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
    PosQueries pos;

    private long nextNumber = 1;

    @BeforeEach
    void clean() {
        superuserJdbc()
                .execute("truncate table pos.receipt_tender, pos.receipt_line, pos.receipt, pos.till_session_close,"
                        + " pos.till_session");
        superuserJdbc().update("delete from kernel.numbering_series where owner_entity_id = ?", MPCS);
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
        UUID session = Ids.next();
        sessions.handle(
                new RecordSession(
                        session, false, null, null, null, MORNING.minusSeconds(600), BigDecimal.ZERO, null, null, null),
                device());
        return session;
    }

    private static RecordSession close(UUID session, Instant at, String counted) {
        return new RecordSession(
                session,
                true,
                null,
                null,
                null,
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
        Scope scope = new Scope(MPCS, SHOP);
        return new ScopeContext(
                null, DEVICE, MPCS, List.of(scope), scope, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private static ScopeContext entityWide() {
        return ScopeContext.dev(UUID.randomUUID(), MPCS, null);
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String id) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(id));
    }
}
