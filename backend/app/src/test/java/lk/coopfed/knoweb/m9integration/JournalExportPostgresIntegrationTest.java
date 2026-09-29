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
                .execute("truncate table integration.journal_line, integration.journal_export,"
                        + " integration.journal_posting");
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

        // 20:30 UTC on the 10th is the 11th in Colombo: the business day of the posting.
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
        assertThat(file.lines().toList())
                .hasSize(9)
                .startsWith("entry,date,doc_type,doc_number,line_kind,side,account_role,debit,credit,document_id");
        assertThat(file).contains("1,2026-08-05,INV,D101-INV-000001,GOODS,SELLER,RECEIVABLE,1000.00,0.00," + invoice);
        assertThat(file).contains("1,2026-08-05,INV,D101-INV-000001,GOODS,SELLER,REVENUE,0.00,1000.00," + invoice);
        assertThat(export.contentHash())
                .isEqualTo(lk.coopfed.knoweb.m9integration.internal.journal.JournalFile.sha256(file));

        assertThat(kernel.committedAudit()).extracting(a -> a.eventType()).containsExactly("JOURNAL_EXPORT_GENERATED");
        assertThat(kernel.committedEvents())
                .containsExactly(new JournalExportGenerated(
                        exportId,
                        FROM,
                        TO,
                        4,
                        new BigDecimal("1880.00"),
                        new BigDecimal("1880.00"),
                        export.contentHash()));

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
        ObjectNode posting = json.createObjectNode();
        posting.put("lineKind", lineKind);
        posting.put("side", "SELLER");
        posting.put("debitRole", debit);
        posting.put("creditRole", credit);
        posting.put("amountSource", source);
        posting.put("amount", new BigDecimal(amount));
        return posting;
    }

    /** journal.postings_ready.v1 as the kernel hands it to a consumer of every type, in the seller's scope. */
    private void deliver(UUID documentId, String type, String number, String occurredAt, ObjectNode... postings) {
        ObjectNode payload = json.createObjectNode();
        payload.put("documentId", documentId.toString());
        payload.put("docTypeCode", type);
        payload.put("docNumberDisplay", number);
        payload.put("ownerEntityId", SELLER.toString());
        ArrayNode list = payload.putArray("postings");
        for (ObjectNode posting : postings) {
            list.add(posting);
        }
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventType", "journal.postings_ready.v1");
        envelope.put("eventId", Ids.next().toString());
        envelope.put("ownerEntityId", SELLER.toString());
        envelope.put("occurredAt", Instant.parse(occurredAt).toString());
        envelope.set("payload", payload);
        consumer(envelope, system(SELLER));
    }

    /** The consumer's method, found by its annotation as the kernel finds it (the class is the module's own). */
    private void consumer(JsonNode envelope, ScopeContext scope) {
        try {
            Object bean = context.getBean(
                    Class.forName("lk.coopfed.knoweb.m9integration.internal.journal.JournalPostingsConsumer"));
            for (var method : bean.getClass().getMethods()) {
                EventConsumer annotation = method.getAnnotation(EventConsumer.class);
                if (annotation != null && annotation.consumer().equals("m9.journal")) {
                    method.setAccessible(true);
                    method.invoke(bean, envelope, scope);
                    return;
                }
            }
            throw new IllegalStateException("The journal consumer has no @EventConsumer method");
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
