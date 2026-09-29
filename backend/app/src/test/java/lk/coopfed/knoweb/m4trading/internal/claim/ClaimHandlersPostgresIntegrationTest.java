package lk.coopfed.knoweb.m4trading.internal.claim;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.AddClaimPhoto;
import lk.coopfed.knoweb.m4trading.api.ApproveClaim;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ClaimApproved;
import lk.coopfed.knoweb.m4trading.api.ClaimPhotoAdded;
import lk.coopfed.knoweb.m4trading.api.ClaimRaised;
import lk.coopfed.knoweb.m4trading.api.ClaimRejected;
import lk.coopfed.knoweb.m4trading.api.ClaimReturnDispatched;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.CreditNoteIssued;
import lk.coopfed.knoweb.m4trading.api.DispatchClaimReturn;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.RaiseClaim;
import lk.coopfed.knoweb.m4trading.api.RejectClaim;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
import lk.coopfed.knoweb.m4trading.query.ClaimQueries;
import lk.coopfed.knoweb.m4trading.query.ClaimView;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
import lk.coopfed.knoweb.m4trading.query.GrnView;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Claims (M4-06; 24A section 6; doc 24 sections 3.5, 4.5 and flow 6.3): the buyer raises a claim
 * on a confirmed GRN with photographs; the seller approves it in part with the credit note issued
 * in the same act, or rejects it; the buyer sends accepted goods back. Every guard, and what each
 * handler audits and publishes. Neither party writes the other's rows.
 */
@Import({TradingFlow.class, MemoryObjectStore.class})
class ClaimHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TradingFlow flow;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

    @Autowired
    IssueInvoiceHandler issueInvoice;

    @Autowired
    RaiseClaimHandler raise;

    @Autowired
    AddClaimPhotoHandler photo;

    @Autowired
    ApproveClaimHandler approve;

    @Autowired
    RejectClaimHandler reject;

    @Autowired
    DispatchClaimReturnHandler dispatchReturn;

    @Autowired
    ClaimQueries claims;

    @Autowired
    GrnQueries grns;

    @Autowired
    DeliveryQueries deliveries;

    @Autowired
    InvoiceQueries invoices;

    private UUID grnId;
    private UUID riceLine;
    private UUID invoiceId;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    /** 10 rice and 4 dhal sent and received in full; the seller invoices when asked. */
    private void receive(boolean invoice) {
        UUID noteId = flow.dispatchedNote(SHOP);
        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();
        grnId = capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "10"), line(DHAL, "4"))), buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());
        riceLine = grns.getGrn(grnId, buyer()).orElseThrow().lines().stream()
                .filter(line -> RICE.equals(line.skuId()))
                .map(GrnView.GrnLineView::lineId)
                .findFirst()
                .orElseThrow();
        if (invoice) {
            invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        }
        kernel.reset();
    }

    private UUID raiseRice(String qty, boolean returnRequested) {
        return raise.handle(
                new RaiseClaim(
                        grnId,
                        "DAMAGED",
                        returnRequested,
                        "Crushed bags",
                        List.of(new RaiseClaim.Line(riceLine, new BigDecimal(qty)))),
                buyer());
    }

    @Test
    void theBuyerRaisesTheSellerApprovesInPartWithACreditNoteAndTheBuyerSendsTheGoodsBack() {
        receive(true);

        UUID claimId = raiseRice("3", true);

        ClaimView raised = claims.getClaim(claimId, seller()).orElseThrow();
        assertThat(raised.status()).isEqualTo(ClaimView.RAISED);
        assertThat(raised.docNumberDisplay()).isNotBlank();
        assertThat(raised.buyerEntityId()).isEqualTo(BUYER);
        assertThat(raised.sellerEntityId()).isEqualTo(SELLER);
        assertThat(raised.locationId()).isEqualTo(SHOP);
        assertThat(raised.invoiceId()).isEqualTo(invoiceId);
        assertThat(raised.lines()).singleElement().satisfies(line -> {
            assertThat(line.claimedQty()).isEqualByComparingTo("3");
            assertThat(line.batchId()).isNotNull();
            assertThat(line.unitPrice()).isEqualByComparingTo("120");
        });
        assertThat(claims.listClaims(OrderQueries.Role.SELLER, seller()))
                .extracting(ClaimView::claimId)
                .containsExactly(claimId);
        assertThat(claims.listClaims(OrderQueries.Role.BUYER, buyer()))
                .extracting(ClaimView::claimId)
                .containsExactly(claimId);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("CLAIM_RAISED");
        assertThat(events(ClaimRaised.class)).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("DAMAGED");
            assertThat(event.sellerEntityId()).isEqualTo(SELLER);
            assertThat(event.returnRequested()).isTrue();
            assertThat(event.lines()).singleElement().satisfies(line -> assertThat(line.qty())
                    .isEqualByComparingTo("3"));
        });

        // The seller accepts two of the three bags, with the goods to come back.
        kernel.reset();
        UUID claimLine = raised.lines().get(0).claimLineId();
        UUID creditNoteId = approve.handle(
                new ApproveClaim(
                        claimId, "Two bags crushed", true, List.of(new ApproveClaim.Line(claimLine, BigDecimal.TWO))),
                seller());

        ClaimView approved = claims.getClaim(claimId, buyer()).orElseThrow();
        assertThat(approved.status()).isEqualTo(ClaimView.APPROVED);
        assertThat(approved.creditNoteId()).isEqualTo(creditNoteId);
        assertThat(approved.creditNoteDocNumberDisplay()).isNotBlank();
        assertThat(approved.returnRequired()).isTrue();
        assertThat(approved.findings()).isEqualTo("Two bags crushed");
        assertThat(approved.decidedByUserId()).isEqualTo(SELLER_USER);
        assertThat(approved.lines().get(0).approvedQty()).isEqualByComparingTo("2");
        // 2 x 120.00 + 18 % VAT
        assertThat(invoices.balance(invoiceId, buyer()).orElseThrow().creditedAmount())
                .isEqualByComparingTo("283.20");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("CLAIM_APPROVED", "CREDIT_NOTE_ISSUED");
        assertThat(events(ClaimApproved.class)).singleElement().satisfies(event -> {
            assertThat(event.creditNoteId()).isEqualTo(creditNoteId);
            assertThat(event.returnRequired()).isTrue();
            assertThat(event.lines()).singleElement().satisfies(line -> assertThat(line.qty())
                    .isEqualByComparingTo("2"));
        });
        assertThat(events(CreditNoteIssued.class)).singleElement().satisfies(event -> assertThat(event.grossAmount())
                .isEqualByComparingTo("283.20"));
        assertThat(events(JournalPostingsReady.class)).hasSize(1);

        kernel.reset();
        refused(() -> approve.handle(new ApproveClaim(claimId, null, false, List.of()), seller()), "m4.claim.decided");
        refused(() -> reject.handle(new RejectClaim(claimId, "no"), seller()), "m4.claim.decided");
        // The seller cannot send the buyer's goods back, nor the buyer decide its own claim.
        refused(() -> dispatchReturn.handle(new DispatchClaimReturn(claimId), seller()), "m4.claim.not_found");
        assertThat(kernel.committedEvents()).isEmpty();

        dispatchReturn.handle(new DispatchClaimReturn(claimId), buyer());

        assertThat(claims.getClaim(claimId, seller()).orElseThrow().returnedAt())
                .isNotNull();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("CLAIM_RETURN_DISPATCHED");
        assertThat(events(ClaimReturnDispatched.class)).singleElement().satisfies(event -> {
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.locationId()).isEqualTo(SHOP);
            assertThat(event.lines()).singleElement().satisfies(line -> {
                assertThat(line.qty()).isEqualByComparingTo("2");
                assertThat(line.batchId()).isNotNull();
            });
        });
        kernel.reset();
        refused(() -> dispatchReturn.handle(new DispatchClaimReturn(claimId), buyer()), "m4.claim.returned_already");
    }

    @Test
    void theRaiseGuards() {
        receive(false);

        refused(
                () -> raise.handle(
                        new RaiseClaim(
                                grnId, "LOST", false, null, List.of(new RaiseClaim.Line(riceLine, BigDecimal.ONE))),
                        buyer()),
                "m4.claim.kind_invalid");
        refused(
                () -> raise.handle(
                        new RaiseClaim(
                                grnId, "DAMAGED", false, null, List.of(new RaiseClaim.Line(riceLine, BigDecimal.ONE))),
                        seller()),
                "m4.claim.grn_not_found");
        refused(
                () -> raise.handle(new RaiseClaim(grnId, "DAMAGED", false, null, List.of()), buyer()),
                "m4.claim.lines_required");
        refused(
                () -> raise.handle(
                        new RaiseClaim(
                                grnId,
                                "DAMAGED",
                                false,
                                null,
                                List.of(new RaiseClaim.Line(UUID.randomUUID(), BigDecimal.ONE))),
                        buyer()),
                "m4.claim.line_unknown");
        refused(
                () -> raise.handle(
                        new RaiseClaim(
                                grnId,
                                "DAMAGED",
                                false,
                                null,
                                List.of(
                                        new RaiseClaim.Line(riceLine, BigDecimal.ONE),
                                        new RaiseClaim.Line(riceLine, BigDecimal.ONE))),
                        buyer()),
                "m4.claim.line_duplicate");
        refused(() -> raiseRice("0", false), "m4.claim.qty_invalid");
        refused(() -> raiseRice("11", false), "m4.claim.exceeds_received");
        assertThat(kernel.committedEvents()).isEmpty();

        // Seven of the ten are claimed: only three are left to claim.
        raiseRice("7", false);
        refused(() -> raiseRice("4", false), "m4.claim.exceeds_received");

        // Past the window (14 days by default) nothing more is claimed.
        superuserJdbc()
                .update(
                        "update trading.doc_grn set confirmed_at = now() - interval '20 days' where document_id = ?",
                        grnId);
        refused(() -> raiseRice("1", false), "m4.claim.window_closed");
    }

    @Test
    void theDecisionWaitsForTheInvoiceAndForEveryPhotograph() {
        receive(false);
        UUID claimId = raiseRice("2", false);
        refused(
                () -> approve.handle(new ApproveClaim(claimId, null, false, List.of()), seller()),
                "m4.claim.invoice_first");
        invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());

        kernel.reset();
        refused(() -> photo.handle(new AddClaimPhoto(claimId, "image/jpeg", 1000L), seller()), "m4.claim.not_found");
        Attachments.PresignedUpload upload = photo.handle(new AddClaimPhoto(claimId, "image/jpeg", 1000L), buyer());
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("CLAIM_PHOTO_ADDED");
        assertThat(events(ClaimPhotoAdded.class)).singleElement().satisfies(event -> assertThat(event.attachmentId())
                .isEqualTo(upload.attachmentId()));
        assertThat(claims.getClaim(claimId, seller()).orElseThrow().photos())
                .singleElement()
                .satisfies(p -> assertThat(p.status()).isEqualTo("PENDING"));

        kernel.reset();
        refused(
                () -> approve.handle(new ApproveClaim(claimId, null, false, List.of()), seller()),
                "m4.claim.evidence_pending");
        refused(() -> reject.handle(new RejectClaim(claimId, "no"), seller()), "m4.claim.evidence_pending");
        assertThat(kernel.committedEvents()).isEmpty();

        // The verifier found the photograph: the seller may decide.
        superuserJdbc()
                .update(
                        "update kernel.document_attachment set status = 'COMPLETE' where attachment_id = ?",
                        upload.attachmentId());
        UUID claimLine =
                claims.getClaim(claimId, seller()).orElseThrow().lines().get(0).claimLineId();
        refused(
                () -> approve.handle(
                        new ApproveClaim(
                                claimId, null, false, List.of(new ApproveClaim.Line(claimLine, BigDecimal.ZERO))),
                        seller()),
                "m4.claim.nothing_approved");
        refused(
                () -> approve.handle(
                        new ApproveClaim(
                                claimId, null, false, List.of(new ApproveClaim.Line(claimLine, BigDecimal.TEN))),
                        seller()),
                "m4.claim.qty_invalid");
        refused(
                () -> approve.handle(
                        new ApproveClaim(
                                claimId,
                                null,
                                false,
                                List.of(new ApproveClaim.Line(UUID.randomUUID(), BigDecimal.ONE))),
                        seller()),
                "m4.claim.line_unknown");
        refused(() -> approve.handle(new ApproveClaim(claimId, null, false, List.of()), buyer()), "m4.claim.not_found");

        approve.handle(new ApproveClaim(claimId, null, false, List.of()), seller());
        assertThat(claims.getClaim(claimId, buyer())
                        .orElseThrow()
                        .lines()
                        .get(0)
                        .approvedQty())
                .isEqualByComparingTo("2");
        refused(() -> dispatchReturn.handle(new DispatchClaimReturn(claimId), buyer()), "m4.claim.return_not_required");
        refused(() -> photo.handle(new AddClaimPhoto(claimId, "image/jpeg", 1000L), buyer()), "m4.claim.decided");
    }

    @Test
    void aRejectedClaimFreesItsQuantity() {
        receive(true);
        UUID claimId = raiseRice("10", false);
        refused(() -> raiseRice("1", false), "m4.claim.exceeds_received");

        kernel.reset();
        assertThatThrownBy(() -> reject.handle(new RejectClaim(claimId, " "), seller()))
                .isInstanceOf(ProblemException.class);
        reject.handle(new RejectClaim(claimId, "The bags were whole on delivery"), seller());

        ClaimView rejected = claims.getClaim(claimId, buyer()).orElseThrow();
        assertThat(rejected.status()).isEqualTo(ClaimView.REJECTED);
        assertThat(rejected.rejectReason()).isEqualTo("The bags were whole on delivery");
        assertThat(rejected.creditNoteId()).isNull();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("CLAIM_REJECTED");
        assertThat(events(ClaimRejected.class)).singleElement().satisfies(event -> assertThat(event.reason())
                .isEqualTo("The bags were whole on delivery"));
        assertThat(invoices.balance(invoiceId, buyer()).orElseThrow().creditedAmount())
                .isEqualByComparingTo("0");

        raiseRice("1", false);
    }

    private static CaptureGrn.Line line(UUID sku, String received) {
        return new CaptureGrn.Line(sku, "EA", new BigDecimal(received), BigDecimal.ZERO, null, null, null, null);
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
