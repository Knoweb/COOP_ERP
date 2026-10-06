package lk.coopfed.knoweb.m5inventory.internal.writeoff;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.AddWriteOffPhoto;
import lk.coopfed.knoweb.m5inventory.api.ApproveWriteOff;
import lk.coopfed.knoweb.m5inventory.api.LossCategory;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.RejectWriteOff;
import lk.coopfed.knoweb.m5inventory.api.RequestWriteOff;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.SubmitWriteOff;
import lk.coopfed.knoweb.m5inventory.api.WitnessWriteOff;
import lk.coopfed.knoweb.m5inventory.api.WriteOffPosted;
import lk.coopfed.knoweb.m5inventory.api.WriteOffRequested;
import lk.coopfed.knoweb.m5inventory.api.WriteOffSubmitted;
import lk.coopfed.knoweb.m5inventory.api.WriteOffWitnessed;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.m5inventory.query.WriteOffView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Write-offs (25A M5-13; doc 25 section 3.8, flow 6.4): drafted at the location with a category and
 * its lines, issued as a numbered WOF document, witnessed by another person, approved within the
 * approver's limit by someone other than the requester, and posted as WRITE_OFF at the entity
 * average. Photographs where the category or a single-staff location needs them, verified before
 * the witness; at a single-staff location the witness is remote and must hold the approval.
 */
@Import(MemoryObjectStore.class)
class WriteOffPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67d-0000-7000-8000-000000000002");
    private static final UUID WITNESS = UUID.fromString("0190e67d-0000-7000-8000-000000000020");
    private static final UUID APPROVER = UUID.fromString("0190e67d-0000-7000-8000-000000000021");

    @Autowired
    RequestWriteOffHandler request;

    @Autowired
    AddWriteOffPhotoHandler photo;

    @Autowired
    SubmitWriteOffHandler submit;

    @Autowired
    WitnessWriteOffHandler witness;

    @Autowired
    ApproveWriteOffHandler approve;

    @Autowired
    RejectWriteOffHandler reject;

    @Autowired
    StockControlQueries control;

    @Autowired
    InventoryQueries inventory;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID stores;
    private UUID shop;
    private UUID batch;
    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> roles = new ArrayList<>();

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5WO", "MPCS");
        stores = fixture.location(MPCS, "WAREHOUSE");
        shop = fixture.location(MPCS, "SHOP");
        UUID sku = fixture.sku(MPCS, "FLOUR1");
        batch = fixture.batch(sku, MPCS, "F1", LocalDate.of(2026, 9, 1));
        receive(stores, "50");
        receive(shop, "8");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        for (UUID user : users) {
            admin.update("delete from security.user_role where user_id = ?", user);
            admin.update("delete from security.app_user where user_id = ?", user);
        }
        for (UUID role : roles) {
            admin.update("delete from security.role_permission where role_id = ?", role);
            admin.update("delete from security.role where role_id = ?", role);
        }
        fixture.clean();
    }

    @Test
    void theStoresWriteOffExpiredStockWitnessedAndApprovedByOthers() {
        UUID id = request.handle(expired(stores, "2"), at(MPCS, stores));

        WriteOffView draft = control.writeOff(id, own(MPCS)).orElseThrow();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.photosRequired()).isFalse();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("WRITEOFF_REQUESTED");
        assertThat(events(WriteOffRequested.class)).singleElement().satisfies(e -> assertThat(e.category())
                .isEqualTo("EXPIRED"));
        assertThat(qty(stores)).isEqualByComparingTo("50");

        kernel.reset();
        submit.handle(new SubmitWriteOff(id), at(MPCS, stores));
        WriteOffView requested = control.writeOff(id, own(MPCS)).orElseThrow();
        assertThat(requested.status()).isEqualTo("REQUESTED");
        assertThat(requested.documentNo()).isNotBlank();
        assertThat(requested.value()).isEqualByComparingTo("200.00");
        assertThat(requested.band()).isEqualTo(1);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("DOCUMENT_ISSUED", "WRITEOFF_SUBMITTED");
        assertThat(events(WriteOffSubmitted.class)).singleElement().satisfies(e -> assertThat(e.documentNo())
                .isEqualTo(requested.documentNo()));

        kernel.reset();
        assertProblem(() -> witness.handle(new WitnessWriteOff(id), own(MPCS)), "m5.writeoff.witness_is_requester");
        assertProblem(() -> approve.handle(new ApproveWriteOff(id), own(MPCS, APPROVER)), "m5.writeoff.not_witnessed");
        assertThat(kernel.committedAudit()).isEmpty();

        witness.handle(new WitnessWriteOff(id), own(MPCS, WITNESS));
        assertThat(control.writeOff(id, own(MPCS)).orElseThrow().remoteWitness())
                .isFalse();
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("WRITEOFF_WITNESSED");
            assertThat(a.witnessUserId()).isEqualTo(WITNESS);
        });
        assertThat(events(WriteOffWitnessed.class)).singleElement().satisfies(e -> assertThat(e.remoteWitness())
                .isFalse());

        kernel.reset();
        assertProblem(() -> approve.handle(new ApproveWriteOff(id), own(MPCS)), "m5.writeoff.approver_is_requester");
        // wave 2, M5-10: the in-person witness may not approve; a third person does.
        assertProblem(
                () -> approve.handle(new ApproveWriteOff(id), own(MPCS, WITNESS)), "m5.writeoff.approver_is_witness");
        assertThat(kernel.committedAudit()).isEmpty();
        UUID approver = user("inv.writeoff.approve");
        approve.handle(new ApproveWriteOff(id), own(MPCS, approver));

        WriteOffView posted = control.writeOff(id, own(MPCS)).orElseThrow();
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.approverUserId()).isEqualTo(approver);
        assertThat(qty(stores)).isEqualByComparingTo("48");
        assertThat(inventory.movementsOf(id, own(MPCS))).singleElement().satisfies(m -> {
            assertThat(m.movementType()).isEqualTo("WRITE_OFF");
            assertThat(m.qtyDelta()).isEqualByComparingTo("-2");
            assertThat(m.unitCostAtMovement()).isEqualByComparingTo("100");
        });
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "WRITEOFF_POSTED");
        assertThat(events(WriteOffPosted.class)).singleElement().satisfies(e -> assertThat(e.value())
                .isEqualByComparingTo("200.00"));
    }

    @Test
    void theftNeedsAPhotographAndTheWitnessWaitsUntilItIsVerified() {
        UUID id = request.handle(
                new RequestWriteOff(
                        stores,
                        LossCategory.THEFT,
                        null,
                        List.of(new RequestWriteOff.Line(batch, LotCondition.GOOD, BigDecimal.ONE))),
                own(MPCS));
        assertThat(control.writeOff(id, own(MPCS)).orElseThrow().photosRequired())
                .isTrue();

        kernel.reset();
        assertProblem(() -> submit.handle(new SubmitWriteOff(id), own(MPCS)), "m5.writeoff.photos_required");
        assertThat(kernel.committedAudit()).isEmpty();

        Attachments.PresignedUpload upload = photo.handle(new AddWriteOffPhoto(id, "image/jpeg", 2048L), own(MPCS));
        assertThat(upload.url()).isNotNull();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("WRITEOFF_PHOTO_ADDED");
        submit.handle(new SubmitWriteOff(id), own(MPCS));
        assertProblem(
                () -> photo.handle(new AddWriteOffPhoto(id, "image/jpeg", 2048L), own(MPCS)), "m5.writeoff.not_draft");

        kernel.reset();
        assertProblem(
                () -> witness.handle(new WitnessWriteOff(id), own(MPCS, WITNESS)), "m5.writeoff.photos_incomplete");
        assertThat(kernel.committedAudit()).isEmpty();

        superuserJdbc()
                .update(
                        "update kernel.document_attachment set status = 'COMPLETE' where attachment_id = ?",
                        upload.attachmentId());
        witness.handle(new WitnessWriteOff(id), own(MPCS, WITNESS));
        assertThat(control.writeOff(id, own(MPCS)).orElseThrow().photos())
                .singleElement()
                .satisfies(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
    }

    @Test
    void atASingleStaffShopTheWitnessIsRemoteAndMustHoldTheApproval() {
        superuserJdbc().update("update party.location set size_band = 'S' where location_id = ?", shop);
        UUID id = request.handle(expired(shop, "1"), at(MPCS, shop));
        assertProblem(() -> submit.handle(new SubmitWriteOff(id), at(MPCS, shop)), "m5.writeoff.photos_required");
        Attachments.PresignedUpload upload = photo.handle(new AddWriteOffPhoto(id, "image/jpeg", null), at(MPCS, shop));
        submit.handle(new SubmitWriteOff(id), at(MPCS, shop));
        superuserJdbc()
                .update(
                        "update kernel.document_attachment set status = 'COMPLETE' where attachment_id = ?",
                        upload.attachmentId());

        UUID onlyWitness = user(null);
        kernel.reset();
        assertProblem(
                () -> witness.handle(new WitnessWriteOff(id), own(MPCS, onlyWitness)),
                "m5.writeoff.remote_witness_not_allowed");
        assertThat(kernel.committedAudit()).isEmpty();

        UUID accountant = user("inv.writeoff.approve");
        witness.handle(new WitnessWriteOff(id), own(MPCS, accountant));
        assertThat(control.writeOff(id, own(MPCS)).orElseThrow().remoteWitness())
                .isTrue();
        // The remote witness approves too (flow 6.4); only the requester may not.
        approve.handle(new ApproveWriteOff(id), own(MPCS, accountant));
        assertThat(qty(shop)).isEqualByComparingTo("7");
    }

    @Test
    void aValueAboveTheApproversLimitWaitsForTheNextBand() {
        UUID id = request.handle(expired(stores, "2"), own(MPCS));
        submit.handle(new SubmitWriteOff(id), own(MPCS));
        witness.handle(new WitnessWriteOff(id), own(MPCS, WITNESS));
        UUID limited = user("inv.writeoff.approve", "{\"max_value\": 150}");

        kernel.reset();
        assertProblem(() -> approve.handle(new ApproveWriteOff(id), own(MPCS, limited)), "m5.approval.limit_exceeded");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(qty(stores)).isEqualByComparingTo("50");
    }

    // ---- wave 2: approval limits fail closed (M5-09) ------------------------------------------

    @Test
    void theApprovalLimitFailsClosedNoGrantNoValueBelowAndAbove() {
        receive(stores, "250");
        // 260 units at the average of 100: Rs 26,000, above band 1 (Rs 25,000).
        UUID id = witnessed(expired(stores, "260"));
        assertThat(control.writeOff(id, own(MPCS)).orElseThrow().band()).isEqualTo(2);

        kernel.reset();
        // No grant of the permission at the entity: no authority at all.
        UUID nobody = user(null);
        assertProblem(() -> approve.handle(new ApproveWriteOff(id), own(MPCS, nobody)), "m5.approval.limit_exceeded");
        // A grant without max_value (a seeded template, a role from before the limits schema): band 1 only.
        UUID bandOne = user("inv.writeoff.approve");
        assertProblem(() -> approve.handle(new ApproveWriteOff(id), own(MPCS, bandOne)), "m5.approval.limit_exceeded");
        // A limit below the value.
        UUID below = user("inv.writeoff.approve", "{\"max_value\": 25999}");
        assertProblem(() -> approve.handle(new ApproveWriteOff(id), own(MPCS, below)), "m5.approval.limit_exceeded");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(qty(stores)).isEqualByComparingTo("300");

        // A limit at or above it approves.
        UUID above = user("inv.writeoff.approve", "{\"max_value\": 250000}");
        approve.handle(new ApproveWriteOff(id), own(MPCS, above));
        assertThat(control.writeOff(id, own(MPCS)).orElseThrow().status()).isEqualTo("POSTED");
        assertThat(qty(stores)).isEqualByComparingTo("40");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("WRITEOFF_POSTED");
    }

    @Test
    void aGrantWithoutALimitStillApprovesASmallWriteOff() {
        UUID id = witnessed(expired(stores, "2"));
        approve.handle(new ApproveWriteOff(id), own(MPCS, user("inv.writeoff.approve")));
        assertThat(control.writeOff(id, own(MPCS)).orElseThrow().status()).isEqualTo("POSTED");
    }

    @Test
    void aLineOfNoCostRoutesTheWriteOffToBandTwo() {
        UUID samples = fixture.sku(MPCS, "SAMPLE");
        UUID free = fixture.batch(samples, MPCS, "S1", LocalDate.of(2028, 1, 31));
        outer.run(
                own(MPCS),
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        stores,
                                        free,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal("500"),
                                        BigDecimal.ZERO,
                                        null))),
                        own(MPCS)));
        UUID id = witnessed(new RequestWriteOff(
                stores,
                LossCategory.SAMPLES,
                "Given away at the fair",
                List.of(new RequestWriteOff.Line(free, LotCondition.GOOD, new BigDecimal("500")))));
        WriteOffView requested = control.writeOff(id, own(MPCS)).orElseThrow();
        assertThat(requested.value()).isEqualByComparingTo("0.00");
        assertThat(requested.band()).isEqualTo(2);

        kernel.reset();
        UUID bandOne = user("inv.writeoff.approve", "{\"max_value\": 25000}");
        assertProblem(() -> approve.handle(new ApproveWriteOff(id), own(MPCS, bandOne)), "m5.approval.limit_exceeded");
        assertThat(kernel.committedAudit()).isEmpty();

        approve.handle(new ApproveWriteOff(id), own(MPCS, user("inv.writeoff.approve", "{\"max_value\": 250000}")));
        assertThat(control.writeOff(id, own(MPCS)).orElseThrow().band()).isEqualTo(2);
    }

    @Test
    void aFailedUploadDoesNotHoldTheWitnessForEverWhileAnotherPhotographIsThere() {
        UUID id = request.handle(
                new RequestWriteOff(
                        stores,
                        LossCategory.THEFT,
                        null,
                        List.of(new RequestWriteOff.Line(batch, LotCondition.GOOD, BigDecimal.ONE))),
                own(MPCS));
        Attachments.PresignedUpload lost = photo.handle(new AddWriteOffPhoto(id, "image/jpeg", 2048L), own(MPCS));
        Attachments.PresignedUpload kept = photo.handle(new AddWriteOffPhoto(id, "image/jpeg", 2048L), own(MPCS));
        submit.handle(new SubmitWriteOff(id), own(MPCS));
        setPhoto(lost, "FAILED");

        kernel.reset();
        // One still on its way: wait.
        assertProblem(
                () -> witness.handle(new WitnessWriteOff(id), own(MPCS, WITNESS)), "m5.writeoff.photos_incomplete");
        // Another write-off whose only photograph FAILED, where one is required: refused, not held for ever.
        UUID other = request.handle(
                new RequestWriteOff(
                        stores,
                        LossCategory.THEFT,
                        null,
                        List.of(new RequestWriteOff.Line(batch, LotCondition.GOOD, BigDecimal.ONE))),
                own(MPCS));
        Attachments.PresignedUpload only = photo.handle(new AddWriteOffPhoto(other, "image/jpeg", 2048L), own(MPCS));
        submit.handle(new SubmitWriteOff(other), own(MPCS));
        setPhoto(only, "FAILED");
        kernel.reset();
        assertProblem(
                () -> witness.handle(new WitnessWriteOff(other), own(MPCS, WITNESS)), "m5.writeoff.photos_required");
        assertThat(kernel.committedAudit()).isEmpty();

        // One COMPLETE and one FAILED: witnessed, the failed one named in the audit.
        setPhoto(kept, "COMPLETE");
        witness.handle(new WitnessWriteOff(id), own(MPCS, WITNESS));
        assertThat(control.writeOff(id, own(MPCS)).orElseThrow().status()).isEqualTo("WITNESSED");
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("WRITEOFF_WITNESSED");
            assertThat(a.after().toString()).contains(lost.attachmentId().toString());
        });
    }

    @Test
    void twoApprovalsOverTheSameLotsInOppositeOrderDoNotDeadlock() throws Exception {
        UUID batch2 = fixture.batch(
                inventory.balances(stores, null, true, own(MPCS)).get(0).skuId(),
                MPCS,
                "F2",
                LocalDate.of(2028, 3, 31));
        receiveBatch(stores, batch2, "50");
        UUID approver = user("inv.writeoff.approve", "{\"max_value\": 250000}");
        List<UUID[]> pairs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID ab = witnessed(twoLines(batch, batch2));
            UUID ba = witnessed(twoLines(batch2, batch));
            pairs.add(new UUID[] {ab, ba});
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (UUID[] pair : pairs) {
                Future<?> first = pool.submit(() -> approve.handle(new ApproveWriteOff(pair[0]), own(MPCS, approver)));
                Future<?> second = pool.submit(() -> approve.handle(new ApproveWriteOff(pair[1]), own(MPCS, approver)));
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID[] pair : pairs) {
            assertThat(control.writeOff(pair[0], own(MPCS)).orElseThrow().status())
                    .isEqualTo("POSTED");
            assertThat(control.writeOff(pair[1], own(MPCS)).orElseThrow().status())
                    .isEqualTo("POSTED");
        }
    }

    @Test
    void aRejectedWriteOffMovesNoStockAndKeepsItsNumber() {
        UUID id = request.handle(expired(stores, "2"), own(MPCS));
        submit.handle(new SubmitWriteOff(id), own(MPCS));

        kernel.reset();
        assertProblem(() -> reject.handle(new RejectWriteOff(id, null), own(MPCS, APPROVER)), "m5.reason_required");
        reject.handle(new RejectWriteOff(id, "The packs are within date"), own(MPCS, APPROVER));

        WriteOffView rejected = control.writeOff(id, own(MPCS)).orElseThrow();
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.documentNo()).isNotBlank();
        assertThat(rejected.rejectReason()).isEqualTo("The packs are within date");
        assertThat(qty(stores)).isEqualByComparingTo("50");
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> assertThat(a.eventType())
                .isEqualTo("WRITEOFF_REJECTED"));
        assertProblem(
                () -> reject.handle(new RejectWriteOff(id, "again"), own(MPCS, APPROVER)), "m5.writeoff.not_open");
    }

    @Test
    void everyGuardOfTheRequestRefusesWithNothingCommitted() {
        assertProblem(() -> request.handle(expired(Ids.next(), "1"), own(MPCS)), "m5.location.not_in_scope");
        assertProblem(
                () -> request.handle(new RequestWriteOff(stores, null, null, List.of()), own(MPCS)),
                "m5.writeoff.category_required");
        assertProblem(
                () -> request.handle(new RequestWriteOff(stores, LossCategory.EXPIRED, null, List.of()), own(MPCS)),
                "m5.writeoff.lines_required");
        assertProblem(() -> request.handle(expired(stores, "0"), own(MPCS)), "m5.writeoff.line_invalid");
        assertProblem(() -> request.handle(expired(stores, "1.0001"), own(MPCS)), "m5.writeoff.line_invalid");
        assertProblem(() -> request.handle(expired(stores, "51"), own(MPCS)), "m5.writeoff.insufficient_stock");
        assertProblem(
                () -> request.handle(
                        new RequestWriteOff(
                                stores,
                                LossCategory.EXPIRED,
                                null,
                                List.of(new RequestWriteOff.Line(Ids.next(), LotCondition.GOOD, BigDecimal.ONE))),
                        own(MPCS)),
                "m5.batch.not_found");
        // A shop session drafts at its own shop only.
        assertProblem(() -> request.handle(expired(stores, "1"), at(MPCS, shop)), "m5.location.not_in_scope");
        assertProblem(() -> submit.handle(new SubmitWriteOff(Ids.next()), own(MPCS)), "m5.writeoff.not_found");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private RequestWriteOff expired(UUID location, String qty) {
        return new RequestWriteOff(
                location,
                LossCategory.EXPIRED,
                "Past date",
                List.of(new RequestWriteOff.Line(batch, LotCondition.GOOD, new BigDecimal(qty))));
    }

    /** A write-off drafted at the stores, submitted and witnessed in person by another person. */
    private UUID witnessed(RequestWriteOff draft) {
        UUID id = request.handle(draft, own(MPCS));
        submit.handle(new SubmitWriteOff(id), own(MPCS));
        witness.handle(new WitnessWriteOff(id), own(MPCS, WITNESS));
        return id;
    }

    private RequestWriteOff twoLines(UUID first, UUID second) {
        return new RequestWriteOff(
                stores,
                LossCategory.DAMAGED_IN_STORE,
                "Torn",
                List.of(
                        new RequestWriteOff.Line(first, LotCondition.GOOD, BigDecimal.ONE),
                        new RequestWriteOff.Line(second, LotCondition.GOOD, BigDecimal.ONE)));
    }

    private void setPhoto(Attachments.PresignedUpload upload, String status) {
        superuserJdbc()
                .update(
                        "update kernel.document_attachment set status = ? where attachment_id = ?",
                        status,
                        upload.attachmentId());
    }

    private void receive(UUID location, String qty) {
        receiveBatch(location, batch, qty);
    }

    private void receiveBatch(UUID location, UUID theBatch, String qty) {
        ScopeContext scope = own(MPCS);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        location,
                                        theBatch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal(qty),
                                        new BigDecimal("100"),
                                        null))),
                        scope));
    }

    /** A user of the entity holding, entity-wide, a role with the permission (and its limits), or none. */
    private UUID user(String permission) {
        return user(permission, null);
    }

    private UUID user(String permission, String limits) {
        JdbcTemplate admin = superuserJdbc();
        UUID user = Ids.next();
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                        + " values (?, ?, ?, ?, 'BACK_OFFICE', 'ACTIVE')",
                user,
                MPCS,
                "m5wo-" + user,
                "Write-off test user");
        users.add(user);
        if (permission != null) {
            UUID role = Ids.next();
            admin.update(
                    "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                            + " values (?, ?, ?, false, 'OWN', 'ACTIVE')",
                    role,
                    MPCS,
                    "Approver " + role);
            admin.update(
                    "insert into security.role_permission (role_id, permission_code, limits) values (?, ?, ?::jsonb)",
                    role,
                    permission,
                    limits);
            admin.update(
                    "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id)"
                            + " values (?, ?, ?, null)",
                    user,
                    role,
                    MPCS);
            roles.add(role);
        }
        return user;
    }

    private BigDecimal qty(UUID location) {
        return inventory.balances(location, null, true, own(MPCS)).stream()
                .findFirst()
                .orElseThrow()
                .qtyOnHand();
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String id) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(id));
    }
}
