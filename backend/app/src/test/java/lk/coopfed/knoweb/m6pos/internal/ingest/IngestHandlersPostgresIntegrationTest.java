package lk.coopfed.knoweb.m6pos.internal.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
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
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ingest handlers' guards (the shape of a fact, never its business) with nothing committed,
 * and what a receipt central finds odd becomes: a flag and a REVIEW record, the receipt kept.
 */
class IngestHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e6a1-0000-7000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("0190e6a1-0000-7000-8000-000000000101");
    private static final UUID DEVICE = UUID.fromString("0190e6a1-0000-7000-8000-000000000301");

    @Autowired
    RecordReceiptHandler receipts;

    @Autowired
    RecordSessionHandler sessions;

    @Autowired
    PosQueries pos;

    @BeforeEach
    void clean() {
        superuserJdbc()
                .execute("truncate table pos.receipt_tender, pos.receipt_line, pos.receipt, pos.till_session_close,"
                        + " pos.till_session");
        kernel.reset();
    }

    @AfterEach
    void cleanAfterwards() {
        clean();
    }

    @Test
    void aReceiptCentralFindsOddIsKeptAndFlagged() {
        UUID elsewhere = Ids.next();
        UUID id = receipts.handle(receipt(Ids.next(), elsewhere, Ids.next()), device());

        assertThat(pos.receipts(SHOP, device())).singleElement().satisfies(r -> {
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
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(pos.receipts(SHOP, device())).isEmpty();
    }

    private static RecordReceipt receipt(UUID documentId, UUID location, UUID session) {
        return new RecordReceipt(
                documentId,
                location,
                null,
                DEVICE,
                session,
                Ids.next(),
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
