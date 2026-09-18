package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentLinkRecord;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentStateHistoryRecord;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doc 18 Part C invariants the stub must honour, because the 19A trigger will:
 * C-I1 an issued document changes only in status, and lines, links and history are append-only.
 */
class InMemoryDocumentBaseRepositoryTest {

    private final InMemoryDocumentBaseRepository repo = new InMemoryDocumentBaseRepository();
    private final UUID entity = Ids.next();
    private final UUID operator = Ids.next();

    @Test
    void aDraftMayChangeFreely() {
        DocumentRecord draft = draft(Ids.next());
        repo.save(draft);

        DocumentRecord amended = withNotes(draft, "amended while draft");
        repo.save(amended);

        assertEquals("amended while draft", repo.findById(draft.id()).orElseThrow().notes());
    }

    @Test
    void anIssuedDocumentRejectsAnyChangeButStatus() {
        DocumentRecord issued = issued(Ids.next());
        repo.save(issued);

        ProblemException problem = assertThrows(
                ProblemException.class,
                () -> repo.save(withNotes(issued, "edited after issue")));

        assertEquals("document.immutable", problem.messageId());

        repo.save(issued.withStatus("PAID"));

        assertEquals("PAID", repo.findById(issued.id()).orElseThrow().status());
    }

    @Test
    void aStateTransitionMovesTheStatusAndIsKept() {
        DocumentRecord issued = issued(Ids.next());
        repo.save(issued);

        repo.addStateTransition(new DocumentStateHistoryRecord(
                Ids.next(), issued.id(), "ISSUED", "VOID",
                Instant.now(), LocalDateTime.now(), operator, null, "CUSTOMER_LEFT", null));

        assertEquals("VOID", repo.findById(issued.id()).orElseThrow().status());
        assertEquals(1, repo.findHistory(issued.id()).size());
        assertEquals("VOID", repo.findHistory(issued.id()).get(0).toStatus());
    }

    @Test
    void linesAppendAndAreReadBack() {
        DocumentRecord issued = issued(Ids.next());
        repo.save(issued);

        repo.saveLines(issued.id(), List.of(line(issued.id(), 1)));
        repo.saveLines(issued.id(), List.of(line(issued.id(), 2)));

        assertEquals(2, repo.findLines(issued.id()).size());
        assertTrue(repo.findLines(Ids.next()).isEmpty());
    }

    @Test
    void linksAreFoundFromBothDocuments() {
        DocumentRecord original = issued(Ids.next());
        DocumentRecord reversal = issued(Ids.next());
        repo.save(original);
        repo.save(reversal);

        repo.addLink(new DocumentLinkRecord(
                reversal.id(), original.id(), LinkType.REVERSES, null, Instant.now(), operator));

        assertEquals(1, repo.findLinks(original.id()).size());
        assertEquals(1, repo.findLinks(reversal.id()).size());
        assertEquals(LinkType.REVERSES, repo.findLinks(original.id()).get(0).linkType());
    }

    private DocumentRecord draft(UUID id) {
        return new DocumentRecord(
                id, "RCT", null, null, null,
                entity, null, null, null, null,
                "DRAFT", null, null, LocalDate.now(), operator,
                "LKR", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                null, DocumentOrigin.ONLINE, null, "draft");
    }

    private DocumentRecord issued(UUID id) {
        return new DocumentRecord(
                id, "RCT", Ids.next(), 42L, "M042-S07-T1-RCT-0000042",
                entity, null, null, null, null,
                "ISSUED", Instant.now(), LocalDateTime.now(), LocalDate.now(), operator,
                "LKR", new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("100.00"), null,
                "hash", DocumentOrigin.ONLINE, null, "issued");
    }

    private static DocumentRecord withNotes(DocumentRecord d, String notes) {
        return new DocumentRecord(
                d.id(), d.docTypeCode(), d.seriesId(), d.docNumber(), d.docNumberDisplay(),
                d.ownerEntityId(), d.counterpartyEntityId(), d.locationId(), d.tillPositionId(), d.deviceId(),
                d.status(), d.issuedAt(), d.issuedLocal(), d.businessDate(), d.operatorUserId(),
                d.currency(), d.netAmount(), d.taxAmount(), d.grossAmount(), d.referenceDocumentId(),
                d.contentHash(), d.origin(), d.deviceSeq(), notes);
    }

    private static DocumentLineRecord line(UUID documentId, int lineNo) {
        return new DocumentLineRecord(
                Ids.next(), documentId, lineNo, Ids.next(), null, "EA",
                new BigDecimal("1.000"), new BigDecimal("50.0000"), null, null, null,
                null, null, null, null, new BigDecimal("50.00"), null, null, null);
    }
}
