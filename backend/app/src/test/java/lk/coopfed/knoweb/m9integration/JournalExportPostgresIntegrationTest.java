package lk.coopfed.knoweb.m9integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.JournalExportGenerated;
import lk.coopfed.knoweb.m9integration.api.JournalPostingsRecorded;
import lk.coopfed.knoweb.m9integration.api.RequestJournalExport;
import lk.coopfed.knoweb.m9integration.internal.journal.JournalFileV1;
import lk.coopfed.knoweb.m9integration.internal.journal.JournalFileV2;
import lk.coopfed.knoweb.m9integration.internal.journal.JournalFiles;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.AccountTotal;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The accounting export end to end (29A sections 6.2 and 9): the postings of M4's
 * journal.postings_ready.v1 as the consumer receives them, the export with balanced lines and its
 * totals by account role, each posting exported once (a second export over the same period has
 * nothing to take, a later posting goes into the next one), the reconciliation that recomputes
 * the totals and the file's hash, and the entity's rows invisible to another entity.
 *
 * <p>The documents are dated in August 2026 by their events' time, never by today.
 */
class JournalExportPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER = UUID.fromString("0190e9a0-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0190e9a0-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e9a0-0000-7000-8000-000000000010");
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Autowired
    ApplicationContext context;

    @Autowired
    Handles<RequestJournalExport, UUID> request;

    @Autowired
    IntegrationQueries queries;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void clean() {
        superuserJdbc()
                .execute("truncate table integration.journal_line, integration.journal_export_file,"
                        + " integration.journal_export, integration.journal_posting");
        kernel.reset();
    }

    @Test
    void theConsumerHoldsTheDocumentsPostingsOnceAtTheDayOfItsEvent() {
        UUID invoice = Ids.next();
        deliver(
                invoice,
                "INV",
                "D101-INV-000001",
                "2026-08-10T20:30:00Z",
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "1000.00"),
                posting("GOODS", "RECEIVABLE", "VAT_OUTPUT", "tax", "180.00"));

        // The payload has no businessDate (an event published before wave 2, replayed): 20:30 UTC on
        // the 10th is the 11th in Colombo, the business day of the posting.
        assertThat(superuserJdbc()
                        .queryForList(
                                "select business_date::text as day, seq, amount from integration.journal_posting"
                                        + " where document_id = ? order by seq",
                                invoice))
                .extracting(row -> row.get("day") + " " + row.get("seq") + " " + row.get("amount"))
                .containsExactly("2026-08-11 1 1000.00", "2026-08-11 2 180.00");
        assertThat(kernel.committedAudit()).extracting(a -> a.eventType()).containsExactly("JOURNAL_POSTINGS_RECORDED");
        assertThat(kernel.committedEvents()).containsExactly(new JournalPostingsRecorded(invoice, "INV", 2));

        // The same event again (a redelivery): nothing more, nothing audited or published.
        kernel.reset();
        deliver(
                invoice,
                "INV",
                "D101-INV-000001",
                "2026-08-10T20:30:00Z",
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "1000.00"),
                posting("GOODS", "RECEIVABLE", "VAT_OUTPUT", "tax", "180.00"));
        assertThat(count("integration.journal_posting")).isEqualTo(2);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aPostingTakesItsDocumentsBusinessDateAndOnlyTheOwnersSide() {
        // Wave 2 (CR-29-1 item 4): the document's own date, not the event's day (the 11th in Colombo).
        // And only the issuer's side: an invoice's BUYER lines are the buyer's books, not the
        // seller's, and are left out here (buyer-postings decision (3)).
        UUID invoice = Ids.next();
        deliverAs(
                SELLER,
                invoice,
                "INV",
                "D101-INV-000002",
                "2026-08-10T20:30:00Z",
                LocalDate.of(2026, 8, 5),
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "1000.00"),
                posting("BUYER", "GOODS", "GRN_ACCRUAL", "PAYABLE", "net", "1000.00"));

        assertThat(superuserJdbc()
                        .queryForList(
                                "select business_date::text as day, side, debit_role from integration.journal_posting"
                                        + " where document_id = ? order by seq",
                                invoice))
                .extracting(row -> row.get("day") + " " + row.get("side") + " " + row.get("debit_role"))
                .containsExactly("2026-08-05 SELLER RECEIVABLE");
        assertThat(kernel.committedEvents()).containsExactly(new JournalPostingsRecorded(invoice, "INV", 1));

        // A GRN is the receiver's document (issuer BUYER): its BUYER lines are its owner's own books.
        kernel.reset();
        UUID grn = Ids.next();
        deliverAs(
                OTHER,
                grn,
                "GRN",
                "M101-GRN-000001",
                "2026-08-10T04:00:00Z",
                LocalDate.of(2026, 8, 10),
                posting("BUYER", "GOODS", "INVENTORY", "GRN_ACCRUAL", "cost", "1000.00"));
        assertThat(superuserJdbc()
                        .queryForList(
                                "select owner_entity_id, side, debit_role, credit_role, amount::text as amount"
                                        + " from integration.journal_posting where document_id = ?",
                                grn))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("owner_entity_id")).isEqualTo(OTHER);
                    assertThat(row.get("side")).isEqualTo("BUYER");
                    assertThat(row.get("debit_role")).isEqualTo("INVENTORY");
                    assertThat(row.get("credit_role")).isEqualTo("GRN_ACCRUAL");
                    assertThat(row.get("amount")).isEqualTo("1000.00");
                });

        // An event with nothing of its owner's side records nothing, audits nothing.
        kernel.reset();
        deliverAs(
                SELLER,
                Ids.next(),
                "CN",
                "D101-CN-000001",
                "2026-08-10T04:00:00Z",
                LocalDate.of(2026, 8, 10),
                posting("BUYER", "GOODS", "PAYABLE", "INVENTORY", "net", "10.00"));
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void anExportTakesThePeriodsPostingsBalancedAndEachOnlyOnce() {
        UUID invoice = Ids.next();
        UUID receipt = Ids.next();
        UUID reversal = Ids.next();
        deliver(
                invoice,
                "INV",
                "D101-INV-000001",
                "2026-08-05T04:00:00Z",
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "1000.00"),
                posting("GOODS", "RECEIVABLE", "VAT_OUTPUT", "tax", "180.00"));
        deliver(
                receipt,
                "PRC",
                "D101-PRC-000001",
                "2026-08-12T04:00:00Z",
                posting("RECEIPT", "BANK_OR_CASH", "RECEIVABLE", "applied", "500.00"));
        // A reversal written as a negated amount is the same entry with the roles exchanged.
        deliver(
                reversal,
                "PRC",
                "D101-PRC-000002",
                "2026-08-20T04:00:00Z",
                posting("RECEIPT", "BANK_OR_CASH", "RECEIVABLE", "applied", "-200.00"));
        // Outside the period: not taken.
        deliver(
                Ids.next(),
                "INV",
                "D101-INV-000009",
                "2026-09-02T04:00:00Z",
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "70.00"));
        kernel.reset();

        assertThat(queries.pending(FROM, TO, user(SELLER)).postings()).isEqualTo(4);

        UUID exportId = request.handle(new RequestJournalExport(FROM, TO), user(SELLER));

        IntegrationQueries.JournalExportView export =
                queries.export(exportId, user(SELLER)).orElseThrow();
        assertThat(export.status()).isEqualTo("GENERATED");
        assertThat(export.format()).isEqualTo("CSV");
        assertThat(export.lineCount()).isEqualTo(4);
        assertThat(export.totalDebit()).isEqualByComparingTo("1880.00");
        assertThat(export.totalCredit()).isEqualByComparingTo("1880.00");

        List<IntegrationQueries.JournalLineView> lines = queries.lines(exportId, user(SELLER));
        assertThat(lines)
                .extracting(l -> l.seq() + " " + l.docNumberDisplay() + " " + l.debitRole() + ">" + l.creditRole() + " "
                        + l.amount().toPlainString())
                .containsExactly(
                        "1 D101-INV-000001 RECEIVABLE>REVENUE 1000.00",
                        "2 D101-INV-000001 RECEIVABLE>VAT_OUTPUT 180.00",
                        "3 D101-PRC-000001 BANK_OR_CASH>RECEIVABLE 500.00",
                        "4 D101-PRC-000002 RECEIVABLE>BANK_OR_CASH 200.00");

        IntegrationQueries.Reconciliation reconciliation =
                queries.reconciliation(exportId, user(SELLER)).orElseThrow();
        assertThat(reconciliation.balanced()).isTrue();
        assertThat(reconciliation.matchesRecordedTotals()).isTrue();
        assertThat(reconciliation.matchesRecordedHash()).isTrue();
        Map<String, String> byRole = reconciliation.accounts().stream()
                .collect(Collectors.toMap(
                        AccountTotal::role,
                        a -> a.debit().toPlainString() + "/" + a.credit().toPlainString()));
        assertThat(byRole)
                .containsEntry("RECEIVABLE", "1380.00/500.00")
                .containsEntry("REVENUE", "0/1000.00")
                .containsEntry("VAT_OUTPUT", "0/180.00")
                .containsEntry("BANK_OR_CASH", "500.00/200.00");

        String file = queries.file(exportId, user(SELLER)).orElseThrow();
        assertThat(file.lines().toList()).hasSize(9).startsWith(JournalFileV2.HEADER);
        assertThat(file).contains("1,2026-08-05,INV,D101-INV-000001,GOODS,SELLER,RECEIVABLE,1000.00,0.00," + invoice);
        assertThat(file).contains("1,2026-08-05,INV,D101-INV-000001,GOODS,SELLER,REVENUE,0.00,1000.00," + invoice);
        assertThat(export.contentHash()).isEqualTo(JournalFiles.sha256(file));

        assertThat(kernel.committedAudit()).extracting(a -> a.eventType()).containsExactly("JOURNAL_EXPORT_GENERATED");
        assertThat(kernel.committedEvents())
                .containsExactly(new JournalExportGenerated(
                        exportId,
                        FROM,
                        TO,
                        4,
                        new BigDecimal("1880.00"),
                        new BigDecimal("1880.00"),
                        export.contentHash(),
                        false));
        // The file is stored with the export (CR-29-1 item 2): its bytes are what the hash names.
        assertThat(file).startsWith(JournalFileV2.HEADER).contains(",FINAL\r\n");
        assertThat(superuserJdbc()
                        .queryForMap(
                                "select format_version::int as format_version, convert_from(content, 'UTF8') as content,"
                                        + " content_hash from integration.journal_export_file where export_id = ?",
                                exportId))
                .containsEntry("format_version", 2)
                .containsEntry("content", file)
                .containsEntry("content_hash", export.contentHash());

        // The same period again: every posting is taken, nothing is committed.
        kernel.reset();
        ProblemException again = assertThrows(
                ProblemException.class, () -> request.handle(new RequestJournalExport(FROM, TO), user(SELLER)));
        assertThat(again.messageId()).isEqualTo("m9.journal.nothing_to_export");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // A posting of the period that arrives later goes into the next export alone: the supplement.
        deliver(
                Ids.next(),
                "CN",
                "D101-CN-000001",
                "2026-08-25T04:00:00Z",
                posting("GOODS", "REVENUE", "RECEIVABLE", "net", "50.00"));
        UUID supplement = request.handle(new RequestJournalExport(FROM, TO), user(SELLER));
        assertThat(queries.lines(supplement, user(SELLER)))
                .extracting(IntegrationQueries.JournalLineView::docNumberDisplay)
                .containsExactly("D101-CN-000001");
        assertThat(queries.exports(user(SELLER)))
                .extracting(IntegrationQueries.JournalExportView::exportId)
                .containsExactly(supplement, exportId);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) - count(distinct posting_id) from integration.journal_line",
                                Long.class))
                .isZero();
    }

    @Test
    void oneInvoiceGivesTheSellerAndTheBuyerEachTheirOwnPostings() {
        // Wave 2 (CR-19A-13, buyer-postings (2)): the seller's event carries both sides; the
        // kernel runs m9.journal in the seller's scope and m9.journal.buyer in the buyer's. The
        // two record the same document id as two entities' books, held apart by the owner.
        UUID invoice = Ids.next();
        ObjectNode payload = payload(
                SELLER,
                invoice,
                "INV",
                "D101-INV-000003",
                LocalDate.of(2026, 8, 6),
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "1000.00"),
                posting("GOODS", "RECEIVABLE", "VAT_OUTPUT", "tax", "180.00"),
                posting("BUYER", "GOODS", "GRN_ACCRUAL", "PAYABLE", "net", "1000.00"),
                posting("BUYER", "GOODS", "VAT_INPUT", "PAYABLE", "tax", "180.00"));
        payload.put("counterpartyEntityId", OTHER.toString());
        consumer(envelope(SELLER, payload), system(SELLER));
        assertThat(kernel.committedEvents()).containsExactly(new JournalPostingsRecorded(invoice, "INV", 2));

        kernel.reset();
        buyerConsumer(payload, system(OTHER));
        assertThat(kernel.committedAudit()).extracting(a -> a.eventType()).containsExactly("JOURNAL_POSTINGS_RECORDED");
        assertThat(kernel.committedEvents()).containsExactly(new JournalPostingsRecorded(invoice, "INV", 2));

        // Both first postings are seq 1 of the same document: the key names the owner (V0006).
        assertThat(superuserJdbc()
                        .queryForList(
                                "select owner_entity_id, seq, side, debit_role, credit_role from integration.journal_posting"
                                        + " where document_id = ? order by owner_entity_id, seq",
                                invoice))
                .extracting(row -> row.get("owner_entity_id") + " " + row.get("seq") + " " + row.get("side") + " "
                        + row.get("debit_role") + ">" + row.get("credit_role"))
                .containsExactly(
                        SELLER + " 1 SELLER RECEIVABLE>REVENUE",
                        SELLER + " 2 SELLER RECEIVABLE>VAT_OUTPUT",
                        OTHER + " 1 BUYER GRN_ACCRUAL>PAYABLE",
                        OTHER + " 2 BUYER VAT_INPUT>PAYABLE");

        // Each under its own own_read: the seller's export has the receivable, the buyer's the payable.
        assertThat(queries.pending(FROM, TO, user(SELLER)).amount()).isEqualByComparingTo("1180.00");
        assertThat(queries.pending(FROM, TO, user(OTHER)).amount()).isEqualByComparingTo("1180.00");
        UUID sellers = request.handle(new RequestJournalExport(FROM, TO), user(SELLER));
        UUID buyers = request.handle(new RequestJournalExport(FROM, TO), user(OTHER));
        assertThat(queries.lines(sellers, user(SELLER)))
                .extracting(IntegrationQueries.JournalLineView::debitRole)
                .containsExactly("RECEIVABLE", "RECEIVABLE");
        assertThat(queries.lines(buyers, user(OTHER)))
                .extracting(IntegrationQueries.JournalLineView::creditRole)
                .containsExactly("PAYABLE", "PAYABLE");
        assertThat(queries.lines(buyers, user(SELLER))).isEmpty();

        // The buyer consumer again (a redelivery): nothing more. And a credit note's BUYER rows.
        kernel.reset();
        buyerConsumer(payload, system(OTHER));
        assertThat(kernel.committedEvents()).isEmpty();
        UUID creditNote = Ids.next();
        ObjectNode credit = payload(
                SELLER,
                creditNote,
                "CN",
                "D101-CN-000002",
                LocalDate.of(2026, 8, 7),
                posting("GOODS", "REVENUE", "RECEIVABLE", "net", "100.00"),
                posting("BUYER", "GOODS", "PAYABLE", "INVENTORY", "net", "100.00"),
                posting("BUYER", "GOODS", "PAYABLE", "VAT_INPUT", "tax", "18.00"));
        credit.put("counterpartyEntityId", OTHER.toString());
        buyerConsumer(credit, system(OTHER));
        assertThat(superuserJdbc()
                        .queryForList(
                                "select side, debit_role, credit_role from integration.journal_posting"
                                        + " where document_id = ? order by seq",
                                creditNote))
                .extracting(row -> row.get("side") + " " + row.get("debit_role") + ">" + row.get("credit_role"))
                .containsExactly("BUYER PAYABLE>INVENTORY", "BUYER PAYABLE>VAT_INPUT");

        // A GRN event (issuer BUYER) delivered to a counterparty has no SELLER lines: nothing recorded.
        kernel.reset();
        ObjectNode grn = payload(
                OTHER,
                Ids.next(),
                "GRN",
                "M101-GRN-000002",
                LocalDate.of(2026, 8, 8),
                posting("BUYER", "GOODS", "INVENTORY", "GRN_ACCRUAL", "cost", "500.00"));
        grn.put("counterpartyEntityId", SELLER.toString());
        buyerConsumer(grn, system(SELLER));
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void anOpenPeriodIsRefusedUnlessTheExportSaysItIsProvisional() {
        // Tomorrow in the business time zone is open today and still open after midnight, so the
        // test never depends on the hour it runs at.
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(1);
        UUID invoice = Ids.next();
        deliver(
                invoice,
                "INV",
                "D101-INV-000004",
                "2026-08-05T04:00:00Z",
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "10.00"));
        kernel.reset();

        ProblemException open = assertThrows(
                ProblemException.class, () -> request.handle(new RequestJournalExport(FROM, tomorrow), user(SELLER)));
        assertThat(open.messageId()).isEqualTo("m9.journal.period_open");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // With the flag: taken once, like any other export, and marked everywhere.
        UUID exportId = request.handle(new RequestJournalExport(FROM, tomorrow, true), user(SELLER));
        IntegrationQueries.JournalExportView export =
                queries.export(exportId, user(SELLER)).orElseThrow();
        assertThat(export.provisional()).isTrue();
        assertThat(queries.exports(user(SELLER)))
                .extracting(IntegrationQueries.JournalExportView::provisional)
                .containsExactly(true);
        String file = queries.file(exportId, user(SELLER)).orElseThrow();
        assertThat(file)
                .startsWith(JournalFileV2.HEADER)
                .contains(",PROVISIONAL\r\n")
                .doesNotContain(",FINAL\r\n");
        assertThat(JournalFiles.sha256(file)).isEqualTo(export.contentHash());
        assertThat(kernel.committedAudit()).singleElement().satisfies(audit -> assertThat(
                        ((Map<?, ?>) audit.after()).get("provisional"))
                .isEqualTo(true));
        assertThat(kernel.committedEvents())
                .containsExactly(new JournalExportGenerated(
                        exportId,
                        FROM,
                        tomorrow,
                        1,
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        export.contentHash(),
                        true));
        assertThat(queries.reconciliation(exportId, user(SELLER)).orElseThrow().matchesRecordedHash())
                .isTrue();

        // The period is still open: a second provisional export has nothing to take, and what
        // arrives later for the period is the supplement due, never a superseding re-export.
        kernel.reset();
        assertThat(assertThrows(
                                ProblemException.class,
                                () -> request.handle(new RequestJournalExport(FROM, tomorrow, true), user(SELLER)))
                        .messageId())
                .isEqualTo("m9.journal.nothing_to_export");
        assertThat(queries.supplementDue(user(SELLER)).postings()).isZero();
        deliver(
                Ids.next(),
                "INV",
                "D101-INV-000005",
                "2026-08-20T04:00:00Z",
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "25.00"));
        IntegrationQueries.SupplementDue due = queries.supplementDue(user(SELLER));
        assertThat(due.postings()).isEqualTo(1);
        assertThat(due.amount()).isEqualByComparingTo("25.00");
        assertThat(due.earliest()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(due.latest()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(due.upTo()).isEqualTo(tomorrow);
        assertThat(queries.supplementDue(user(OTHER)).postings()).isZero();
        assertThat(queries.supplementDue(user(OTHER)).upTo()).isNull();
    }

    @Test
    void theOriginalAndItsSupplementsAreTheFullExportTakenAtOnce() {
        // 29A's property (CR-29-1): exporting as the postings arrive, in instalments, gives the
        // same lines as one export of everything at the end; only the grouping differs.
        List<ObjectNode> arrivals = List.of(
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "100.00"),
                posting("GOODS", "RECEIVABLE", "VAT_OUTPUT", "tax", "18.00"),
                posting("RECEIPT", "BANK_OR_CASH", "RECEIVABLE", "applied", "50.00"),
                posting("GOODS", "REVENUE", "RECEIVABLE", "net", "20.00"),
                posting("RECEIPT", "BANK_OR_CASH", "RECEIVABLE", "applied", "-10.00"));
        List<String> instalments = new ArrayList<>();
        for (int i = 0; i < arrivals.size(); i++) {
            String type = arrivals.get(i).get("lineKind").asText().equals("RECEIPT") ? "PRC" : "INV";
            deliver(Ids.next(), type, "D101-" + type + "-00001" + i, "2026-08-1" + i + "T04:00:00Z", arrivals.get(i));
            UUID instalment = request.handle(new RequestJournalExport(FROM, TO), user(SELLER));
            queries.lines(instalment, user(SELLER)).forEach(line -> instalments.add(key(line)));
        }

        // The same postings again for another entity, exported once.
        for (int i = 0; i < arrivals.size(); i++) {
            String type = arrivals.get(i).get("lineKind").asText().equals("RECEIPT") ? "PRC" : "INV";
            deliverAs(
                    OTHER,
                    Ids.next(),
                    type,
                    "D101-" + type + "-00001" + i,
                    "2026-08-1" + i + "T04:00:00Z",
                    null,
                    arrivals.get(i));
        }
        UUID full = request.handle(new RequestJournalExport(FROM, TO), user(OTHER));
        List<String> atOnce =
                queries.lines(full, user(OTHER)).stream().map(this::key).toList();

        assertThat(instalments).containsExactlyInAnyOrderElementsOf(atOnce).hasSize(5);
        assertThat(queries.supplementDue(user(SELLER)).postings()).isZero();
    }

    private String key(IntegrationQueries.JournalLineView line) {
        return line.businessDate() + " " + line.docNumberDisplay() + " " + line.debitRole() + ">" + line.creditRole()
                + " " + line.amount().toPlainString();
    }

    @Test
    void anExportMadeBeforeTheFileWasStoredDownloadsAndReconcilesUnchanged() {
        // The demo server's situation after V0006: an export with lines and a hash, no file row.
        // It is served by the frozen version-1 writer, which is what its hash was taken over.
        UUID exportId = Ids.next();
        UUID postingId = Ids.next();
        UUID document = Ids.next();
        superuserJdbc()
                .update(
                        """
                        insert into integration.journal_posting
                               (posting_id, owner_entity_id, document_id, seq, doc_type_code, doc_number_display,
                                line_kind, side, debit_role, credit_role, amount_source, amount, business_date, recorded_at)
                        values (?, ?, ?, 1, 'INV', 'D101-INV-000001', 'GOODS', 'SELLER', 'RECEIVABLE', 'REVENUE', 'net',
                                300.00, '2026-08-05', '2026-08-05T04:00:00Z')
                        """,
                        postingId,
                        SELLER,
                        document);
        IntegrationQueries.JournalLineView line = new IntegrationQueries.JournalLineView(
                1,
                document,
                "INV",
                "D101-INV-000001",
                "GOODS",
                "SELLER",
                "RECEIVABLE",
                "REVENUE",
                new BigDecimal("300.00"),
                LocalDate.of(2026, 8, 5),
                null);
        String versionOne = JournalFileV1.csv(List.of(line));
        superuserJdbc()
                .update(
                        """
                        insert into integration.journal_export
                               (export_id, owner_entity_id, period_from, period_to, format, provisional, status, line_count,
                                total_debit, total_credit, content_hash, generated_at, requested_by, requested_at)
                        values (?, ?, '2026-08-01', '2026-08-31', 'CSV', false, 'GENERATED', 1, 300.00, 300.00, ?,
                                '2026-09-01T04:00:00Z', ?, '2026-09-01T04:00:00Z')
                        """,
                        exportId,
                        SELLER,
                        JournalFiles.sha256(versionOne),
                        USER);
        superuserJdbc()
                .update(
                        """
                        insert into integration.journal_line
                               (line_id, export_id, seq, posting_id, document_id, doc_type_code, doc_number_display,
                                line_kind, side, debit_role, credit_role, amount, business_date, owner_entity_id)
                        values (?, ?, 1, ?, ?, 'INV', 'D101-INV-000001', 'GOODS', 'SELLER', 'RECEIVABLE', 'REVENUE',
                                300.00, '2026-08-05', ?)
                        """,
                        Ids.next(),
                        exportId,
                        postingId,
                        document,
                        SELLER);

        assertThat(queries.file(exportId, user(SELLER))).contains(versionOne);
        assertThat(queries.file(exportId, user(SELLER)).orElseThrow()).doesNotContain(",FINAL");
        IntegrationQueries.Reconciliation reconciliation =
                queries.reconciliation(exportId, user(SELLER)).orElseThrow();
        assertThat(reconciliation.matchesRecordedHash()).isTrue();
        assertThat(reconciliation.matchesRecordedTotals()).isTrue();
    }

    @Test
    void anotherEntitySeesNothingOfTheExport() {
        deliver(
                Ids.next(),
                "INV",
                "D101-INV-000001",
                "2026-08-05T04:00:00Z",
                posting("GOODS", "RECEIVABLE", "REVENUE", "net", "10.00"));
        UUID exportId = request.handle(new RequestJournalExport(FROM, TO), user(SELLER));

        assertThat(queries.exports(user(OTHER))).isEmpty();
        assertThat(queries.export(exportId, user(OTHER))).isEmpty();
        assertThat(queries.lines(exportId, user(OTHER))).isEmpty();
        assertThat(queries.file(exportId, user(OTHER))).isEmpty();
        assertThat(queries.pending(FROM, TO, user(OTHER)).postings()).isZero();
        ProblemException nothing = assertThrows(
                ProblemException.class, () -> request.handle(new RequestJournalExport(FROM, TO), user(OTHER)));
        assertThat(nothing.messageId()).isEqualTo("m9.journal.nothing_to_export");
    }

    @Test
    void theGuardsOfTheRequest() {
        ProblemException backwards = assertThrows(
                ProblemException.class, () -> request.handle(new RequestJournalExport(TO, FROM), user(SELLER)));
        assertThat(backwards.messageId()).isEqualTo("m9.journal.period_invalid");

        ProblemException noUser = assertThrows(
                ProblemException.class, () -> request.handle(new RequestJournalExport(FROM, TO), system(SELLER)));
        assertThat(noUser.messageId()).isEqualTo("m9.journal.entity_required");

        ProblemException atAShop = assertThrows(
                ProblemException.class,
                () -> request.handle(new RequestJournalExport(FROM, TO), ScopeContext.dev(USER, SELLER, Ids.next())));
        assertThat(atAShop.messageId()).isEqualTo("m9.journal.entity_required");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------

    private ObjectNode posting(String lineKind, String debit, String credit, String source, String amount) {
        return posting("SELLER", lineKind, debit, credit, source, amount);
    }

    private ObjectNode posting(
            String side, String lineKind, String debit, String credit, String source, String amount) {
        ObjectNode posting = json.createObjectNode();
        posting.put("lineKind", lineKind);
        posting.put("side", side);
        posting.put("debitRole", debit);
        posting.put("creditRole", credit);
        posting.put("amountSource", source);
        posting.put("amount", new BigDecimal(amount));
        return posting;
    }

    /**
     * journal.postings_ready.v1 as the kernel hands it to a consumer of every type, in the seller's
     * scope, as published before wave 2: no businessDate in the payload.
     */
    private void deliver(UUID documentId, String type, String number, String occurredAt, ObjectNode... postings) {
        deliverAs(SELLER, documentId, type, number, occurredAt, null, postings);
    }

    /** The same, in the owner's scope, with the document's business date when one is given. */
    private void deliverAs(
            UUID owner,
            UUID documentId,
            String type,
            String number,
            String occurredAt,
            LocalDate businessDate,
            ObjectNode... postings) {
        ObjectNode payload = payload(owner, documentId, type, number, businessDate, postings);
        ObjectNode envelope = envelope(owner, payload);
        envelope.put("occurredAt", Instant.parse(occurredAt).toString());
        consumer(envelope, system(owner));
    }

    /** The payload of journal.postings_ready.v1 as M4 publishes it (its field names are the contract). */
    private ObjectNode payload(
            UUID owner, UUID documentId, String type, String number, LocalDate businessDate, ObjectNode... postings) {
        ObjectNode payload = json.createObjectNode();
        payload.put("documentId", documentId.toString());
        payload.put("docTypeCode", type);
        payload.put("docNumberDisplay", number);
        payload.put("ownerEntityId", owner.toString());
        ArrayNode list = payload.putArray("postings");
        for (ObjectNode posting : postings) {
            list.add(posting);
        }
        if (businessDate != null) {
            payload.put("businessDate", businessDate.toString());
        }
        return payload;
    }

    /** The envelope a consumer of every type is handed. */
    private ObjectNode envelope(UUID owner, ObjectNode payload) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventType", "journal.postings_ready.v1");
        envelope.put("eventId", Ids.next().toString());
        envelope.put("ownerEntityId", owner.toString());
        envelope.put("occurredAt", "2026-08-10T04:00:00Z");
        envelope.set("payload", payload);
        return envelope;
    }

    /** The owner's consumer (m9.journal), handed the envelope in the owner's scope. */
    private void consumer(JsonNode envelope, ScopeContext scope) {
        invoke("JournalPostingsConsumer", "m9.journal", envelope, scope);
    }

    /**
     * The counterparty's consumer (m9.journal.buyer), handed the payload in the counterparty's
     * scope, as the kernel does after its own checks (which the kernel's own test proves).
     */
    private void buyerConsumer(JsonNode payload, ScopeContext scope) {
        invoke("BuyerJournalPostingsConsumer", "m9.journal.buyer", payload, scope);
    }

    /** A consumer's method, found by its annotation as the kernel finds it (the classes are the module's own). */
    private void invoke(String className, String consumer, JsonNode event, ScopeContext scope) {
        try {
            Object bean =
                    context.getBean(Class.forName("lk.coopfed.knoweb.m9integration.internal.journal." + className));
            for (var method : bean.getClass().getMethods()) {
                EventConsumer annotation = method.getAnnotation(EventConsumer.class);
                if (annotation != null && annotation.consumer().equals(consumer)) {
                    method.setAccessible(true);
                    method.invoke(bean, event, scope);
                    return;
                }
            }
            throw new IllegalStateException(className + " has no @EventConsumer method for " + consumer);
        } catch (ReflectiveOperationException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e);
        }
    }

    private long count(String table) {
        return superuserJdbc().queryForObject("select count(*) from " + table, Long.class);
    }

    private static ScopeContext user(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    /** The scope the dispatcher gives a consumer: OWN, of the owner, no user. */
    private static ScopeContext system(UUID owner) {
        Scope scope = new Scope(owner, null);
        return new ScopeContext(
                null, null, owner, List.of(scope), scope, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }
}
