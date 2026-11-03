package lk.coopfed.knoweb.m4trading.internal.dispute;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.AddDiscrepancyPhoto;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.DiscrepancyPhotoAdded;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.SettleDiscrepancy;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.SettleDiscrepancyHandler;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.DiscrepancyQueries;
import lk.coopfed.knoweb.m4trading.query.DiscrepancyView;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Discrepancy photographs (ORD-10; doc 24 section 3.4; 19A section 9): the buyer attaches evidence
 * of shortage or damaged goods to an issued discrepancy; both parties can view the photo; settled
 * discrepancies cannot accept new photos; unauthorized/invalid uploads are refused.
 */
@Import({TradingFlow.class, MemoryObjectStore.class})
class DiscrepancyPhotoPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TradingFlow flow;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

    @Autowired
    IssueInvoiceHandler issueInvoice;

    @Autowired
    SettleDiscrepancyHandler settle;

    @Autowired
    AddDiscrepancyPhotoHandler photo;

    @Autowired
    DiscrepancyQueries discrepancies;

    @Autowired
    GrnQueries grns;

    @Autowired
    DeliveryQueries deliveries;

    private UUID grnId;
    private UUID discrepancyId;
    private UUID invoiceId;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    private void receive(String rice, String riceDamaged, boolean invoice) {
        UUID noteId = flow.dispatchedNote(SHOP);
        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();
        grnId = capture.handle(
                new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, rice, riceDamaged), line(DHAL, "4", "0"))),
                buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());
        discrepancyId = grns.getGrn(grnId, buyer()).orElseThrow().discrepancyId();
        if (invoice) {
            invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        }
        kernel.reset();
    }

    private static CaptureGrn.Line line(UUID sku, String received, String damaged) {
        return new CaptureGrn.Line(
                sku, "EA", new BigDecimal(received), new BigDecimal(damaged), null, null, null, null);
    }

    private static void refused(ThrowingCallable action, String messageId) {
        assertThatThrownBy(action)
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo(messageId));
    }

    private <E extends lk.coopfed.knoweb.kernel.api.DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    @Test
    void aPhotoAttachesToADiscrepancyAndIsRetrievableByBothParties() {
        receive("8", "2", true);
        assertThat(discrepancyId).isNotNull();

        // 1. Seller cannot attach photos to the buyer's discrepancy
        refused(
                () -> photo.handle(new AddDiscrepancyPhoto(discrepancyId, "image/jpeg", 1000L), seller()),
                "m4.discrepancy.not_found");

        // 2. Non-existent discrepancy
        refused(
                () -> photo.handle(new AddDiscrepancyPhoto(UUID.randomUUID(), "image/jpeg", 1000L), buyer()),
                "m4.discrepancy.not_found");

        // 3. Buyer successfully attaches a valid photograph
        kernel.reset();
        Attachments.PresignedUpload upload =
                photo.handle(new AddDiscrepancyPhoto(discrepancyId, "image/jpeg", 2048L), buyer());
        assertThat(upload).isNotNull();
        assertThat(upload.attachmentId()).isNotNull();
        assertThat(upload.url()).isNotNull();

        // 4. Audit and event verified
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("DISCREPANCY_PHOTO_ADDED");
        assertThat(events(DiscrepancyPhotoAdded.class)).singleElement().satisfies(event -> {
            assertThat(event.discrepancyId()).isEqualTo(discrepancyId);
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.sellerEntityId()).isEqualTo(SELLER);
            assertThat(event.attachmentId()).isEqualTo(upload.attachmentId());
        });

        // 5. Retrievable in DiscrepancyView by both buyer and seller
        DiscrepancyView buyerView =
                discrepancies.getDiscrepancy(discrepancyId, buyer()).orElseThrow();
        assertThat(buyerView.photos()).singleElement().satisfies(p -> {
            assertThat(p.attachmentId()).isEqualTo(upload.attachmentId());
            assertThat(p.status()).isEqualTo("PENDING");
        });

        DiscrepancyView sellerView =
                discrepancies.getDiscrepancy(discrepancyId, seller()).orElseThrow();
        assertThat(sellerView.photos()).singleElement().satisfies(p -> {
            assertThat(p.attachmentId()).isEqualTo(upload.attachmentId());
            assertThat(p.status()).isEqualTo("PENDING");
        });

        // 6. When the verifier completes the attachment
        superuserJdbc()
                .update(
                        "update kernel.document_attachment set status = 'COMPLETE' where attachment_id = ?",
                        upload.attachmentId());
        DiscrepancyView verifiedView =
                discrepancies.getDiscrepancy(discrepancyId, buyer()).orElseThrow();
        assertThat(verifiedView.photos()).singleElement().satisfies(p -> assertThat(p.status())
                .isEqualTo("COMPLETE"));

        // 7. Settling the discrepancy freezes evidence: no new photos allowed
        settle.handle(new SettleDiscrepancy(discrepancyId, "Damaged count accepted"), seller());
        refused(
                () -> photo.handle(new AddDiscrepancyPhoto(discrepancyId, "image/jpeg", 1000L), buyer()),
                "m4.discrepancy.settled_already");
    }
}
