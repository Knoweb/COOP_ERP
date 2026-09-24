package lk.coopfed.knoweb.kernel.internal.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentIssued;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentLinks;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesHolderChanged;
import lk.coopfed.knoweb.kernel.api.SeriesRegistered;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The document base against PostgreSQL, as the application user (19A section 7, "Tests"):
 * concurrent issuance on one series is dense; a failure after the number leaves neither; an
 * UPDATE of a line fails; REVERSES twice fails; the gap check flags a fabricated gap; and the
 * rows of a document follow the header's row-level security.
 *
 * <p>The test owns the document type ORD through {@link TestBeans}: an order whose lines must
 * have a positive quantity, and whose post-issue hook can be told to fail.
 */
@Import(DocumentBasePostgresIntegrationTest.TestBeans.class)
class DocumentBasePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER = UUID.fromString("0190d000-0000-7000-8000-000000000001");
    private static final UUID BUYER = UUID.fromString("0190d000-0000-7000-8000-000000000002");
    private static final UUID STRANGER = UUID.fromString("0190d000-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190d000-0000-7000-8000-000000000010");
    private static final UUID DEVICE = UUID.fromString("0190d000-0000-7000-8000-000000000020");

    @Autowired
    NumberingService numbering;

    @Autowired
    DocumentIssuance issuance;

    @Autowired
    DocumentLinks links;

    @Autowired
    DocumentBaseRepository documents;

    @Autowired
    GapCheck gapCheck;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestBeans.OrderType orderType;

    @BeforeEach
    void cleanDocumentTables() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("delete from kernel.document_link");
        admin.execute("delete from kernel.document_state_history");
        admin.execute("delete from kernel.document_line");
        admin.execute("delete from kernel.document");
        admin.execute("delete from kernel.numbering_series");
        orderType.failAfterIssue = false;
        orderType.issued.clear();
    }

    // ---- numbering ---------------------------------------------------------------------------

    @Test
    void registeringASeriesTwiceGivesTheSameSeriesOnce() {
        UUID first = inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID second = inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));

        assertThat(second).isEqualTo(first);
        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("SERIES_REGISTERED");
        assertThat(kernel.committedEvents()).hasSize(1).first().isInstanceOf(SeriesRegistered.class);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select prefix from kernel.numbering_series where series_id = ?", String.class, first))
                .isEqualTo("M042-ORD");
    }

    @Test
    void aSeriesIsRegisteredByItsOwnerOnly() {
        assertThatThrownBy(() -> inScope(SELLER, () -> numbering.registerSeries(orderSeries(), scope(SELLER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("series.owner_mismatch");
    }

    @Test
    void aHolderChangeIsAuditedAndPublished() {
        UUID seriesId = inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        kernel.reset();

        inScope(BUYER, () -> {
            numbering.holderChange(List.of(seriesId), DEVICE, scope(BUYER));
            return null;
        });

        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("SERIES_HOLDER_CHANGED");
        assertThat(kernel.committedEvents()).hasSize(1).first().isInstanceOf(SeriesHolderChanged.class);
    }

    // ---- issuance ----------------------------------------------------------------------------

    @Test
    void issuingADraftNumbersItFreezesTotalsAndRecordsEverything() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        kernel.reset();

        UUID id = Ids.next();
        DocumentRecord issued =
                inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));

        assertThat(issued.docNumber()).isEqualTo(1L);
        assertThat(issued.docNumberDisplay()).isEqualTo("M042-ORD-0000001");
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.isIssued()).isTrue();
        assertThat(issued.netAmount()).isEqualByComparingTo("400.00");
        assertThat(issued.taxAmount()).isEqualByComparingTo("30.00");
        assertThat(issued.grossAmount()).isEqualByComparingTo("430.00");
        assertThat(issued.contentHash()).hasSize(64);
        assertThat(issued.businessDate()).isNotNull();

        DocumentRecord stored = inScope(BUYER, () -> documents.findById(id).orElseThrow());
        assertThat(stored.docNumberDisplay()).isEqualTo(issued.docNumberDisplay());
        assertThat(stored.contentHash()).isEqualTo(issued.contentHash());
        assertThat(stored.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(stored.grossAmount()).isEqualByComparingTo(issued.grossAmount());
        assertThat(inScope(BUYER, () -> documents.findLines(id))).hasSize(2);
        assertThat(inScope(BUYER, () -> documents.findHistory(id)))
                .hasSize(1)
                .first()
                .satisfies(row -> {
                    assertThat(row.fromStatus()).isEqualTo("DRAFT");
                    assertThat(row.toStatus()).isEqualTo("ISSUED");
                    assertThat(row.occurredAt()).isEqualTo(issued.issuedAt());
                });

        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("DOCUMENT_ISSUED");
        assertThat(kernel.committedEvents()).hasSize(1).first().isInstanceOf(DocumentIssued.class);
        assertThat(orderType.issued).containsExactly(id);
    }

    @Test
    void theContentHashIsTheSameWhenRecomputedFromTheStoredRows() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();
        DocumentRecord issued =
                inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));

        DocumentRecord stored = inScope(BUYER, () -> documents.findById(id).orElseThrow());
        List<DocumentLineRecord> lines = inScope(BUYER, () -> documents.findLines(id));

        assertThat(ContentHash.of(stored.withContentHash(null), lines)).isEqualTo(issued.contentHash());
    }

    @Test
    void anInvalidDraftTakesNoNumber() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();
        List<DocumentLineRecord> badLines = List.of(line(id, 1, "-1", "100.00", "10.00"));

        assertThatThrownBy(() -> inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), badLines, scope(BUYER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.line.qty_invalid");

        assertThat(nextNumber()).isEqualTo(1L);
        assertThat(superuserJdbc().queryForObject("select count(*) from kernel.document", Long.class))
                .isZero();
    }

    @Test
    void aFailureAfterTheNumberLeavesNeitherTheNumberNorTheDocument() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        kernel.reset();
        orderType.failAfterIssue = true;
        UUID id = Ids.next();

        assertThatThrownBy(() ->
                        inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock movement failed");

        assertThat(nextNumber()).isEqualTo(1L);
        assertThat(superuserJdbc().queryForObject("select count(*) from kernel.document", Long.class))
                .isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.rolledBackAudit()).extracting(r -> r.eventType()).contains("DOCUMENT_ISSUED");

        orderType.failAfterIssue = false;
        UUID next = Ids.next();
        DocumentRecord issued =
                inScope(BUYER, () -> issuance.issue(draft(next, BUYER, SELLER), twoLines(next), scope(BUYER)));
        assertThat(issued.docNumber()).isEqualTo(1L);
    }

    @Test
    void concurrentIssuanceOnOneSeriesIsDense() throws Exception {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));

        int issuers = 4;
        int each = 5;
        ExecutorService pool = Executors.newFixedThreadPool(issuers);
        List<Future<List<Long>>> results = new ArrayList<>();

        try {
            for (int t = 0; t < issuers; t++) {
                results.add(pool.submit(() -> {
                    List<Long> numbers = new ArrayList<>();
                    for (int i = 0; i < each; i++) {
                        UUID id = Ids.next();
                        numbers.add(inScope(
                                        BUYER,
                                        () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)))
                                .docNumber());
                    }
                    return numbers;
                }));
            }

            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> result : results) {
                all.addAll(result.get());
            }

            assertThat(all).hasSize(issuers * each);
            assertThat(Set.copyOf(all)).hasSize(issuers * each);
            assertThat(all.stream().mapToLong(Long::longValue).max().orElseThrow())
                    .isEqualTo(issuers * each);
        } finally {
            pool.shutdownNow();
        }

        assertThat(inScope(BUYER, gapCheck::findGaps)).isEmpty();
    }

    @Test
    void aBilateralTypeNeedsACounterpartyAndAnUnknownTypeIsRefused() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();

        assertThatThrownBy(
                        () -> inScope(BUYER, () -> issuance.issue(draft(id, BUYER, null), twoLines(id), scope(BUYER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.counterparty_required");

        DocumentRecord unknown = draftOfType(id, "XYZ");
        assertThatThrownBy(() -> inScope(BUYER, () -> issuance.issue(unknown, twoLines(id), scope(BUYER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.type_unknown");

        DocumentRecord unowned = draftOfType(id, "INV");
        assertThatThrownBy(() -> inScope(BUYER, () -> issuance.issue(unowned, twoLines(id), scope(BUYER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.type_unowned");
    }

    @Test
    void aClosedSeriesIssuesNothing() {
        UUID seriesId = inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        inScope(BUYER, () -> {
            numbering.closeSeries(seriesId, scope(BUYER));
            return null;
        });
        UUID id = Ids.next();

        assertThatThrownBy(() ->
                        inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("series.closed");
    }

    // ---- immutability ------------------------------------------------------------------------

    @Test
    void anIssuedDocumentChangesOnlyItsStatus() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();
        DocumentRecord issued =
                inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));

        // The repository refuses a changed amount ...
        DocumentRecord tampered = new DocumentRecord(
                issued.id(),
                issued.docTypeCode(),
                issued.seriesId(),
                issued.docNumber(),
                issued.docNumberDisplay(),
                issued.ownerEntityId(),
                issued.counterpartyEntityId(),
                issued.locationId(),
                issued.tillPositionId(),
                issued.deviceId(),
                issued.status(),
                issued.issuedAt(),
                issued.issuedLocal(),
                issued.businessDate(),
                issued.operatorUserId(),
                issued.currency(),
                new BigDecimal("1.00"),
                issued.taxAmount(),
                issued.grossAmount(),
                issued.referenceDocumentId(),
                issued.contentHash(),
                issued.origin(),
                issued.deviceSeq(),
                issued.notes());
        assertThatThrownBy(() -> inScope(BUYER, () -> documents.save(tampered)))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.immutable");

        // ... the trigger refuses it for whoever bypasses the repository, the superuser included ...
        assertThatThrownBy(() ->
                        superuserJdbc().update("update kernel.document set gross_amount = 1 where document_id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("document.immutable");

        // ... a line has no UPDATE privilege at all ...
        assertThatThrownBy(() -> inScope(
                        BUYER, () -> jdbc.update("update kernel.document_line set qty = 99 where document_id = ?", id)))
                .isInstanceOf(DataAccessException.class);

        // ... and a status change through the history is fine.
        inScope(BUYER, () -> {
            documents.addStateTransition(new lk.coopfed.knoweb.kernel.api.DocumentStateHistoryRecord(
                    Ids.next(), id, "ISSUED", "ACCEPTED", Instant.now(), null, USER, null, null, null));
            return null;
        });
        assertThat(inScope(BUYER, () -> documents.findById(id).orElseThrow().status()))
                .isEqualTo("ACCEPTED");
        assertThat(inScope(BUYER, () -> documents.findHistory(id))).hasSize(2);
    }

    // ---- links -------------------------------------------------------------------------------

    @Test
    void reversingTwiceFailsAndSettlingBeyondTheBalanceFails() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID original = Ids.next();
        UUID reversal = Ids.next();
        UUID secondReversal = Ids.next();
        UUID payment = Ids.next();
        for (UUID id : List.of(original, reversal, secondReversal, payment)) {
            inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));
        }

        inScope(BUYER, () -> {
            links.link(reversal, original, LinkType.REVERSES, null, scope(BUYER));
            return null;
        });

        assertThatThrownBy(() -> inScope(BUYER, () -> {
                    links.link(secondReversal, original, LinkType.REVERSES, null, scope(BUYER));
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.link.reversed_already");

        assertThatThrownBy(() -> inScope(BUYER, () -> {
                    links.link(payment, original, LinkType.SETTLES, new BigDecimal("430.01"), scope(BUYER));
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.link.exceeds_balance");

        inScope(BUYER, () -> {
            links.link(payment, original, LinkType.SETTLES, new BigDecimal("430.00"), scope(BUYER));
            return null;
        });

        assertThat(inScope(BUYER, () -> documents.findLinks(original))).hasSize(2);
        assertThat(kernel.committedAudit().stream().filter(r -> "DOCUMENT_LINKED".equals(r.eventType())))
                .hasSize(2);
    }

    @Test
    void supersedingIsForDraftsOnly() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID first = Ids.next();
        UUID second = Ids.next();
        inScope(BUYER, () -> documents.save(draft(first, BUYER, SELLER)));
        inScope(BUYER, () -> documents.save(draft(second, BUYER, SELLER)));

        inScope(BUYER, () -> {
            links.link(second, first, LinkType.SUPERSEDES, null, scope(BUYER));
            return null;
        });

        UUID issued = Ids.next();
        inScope(BUYER, () -> issuance.issue(draft(issued, BUYER, SELLER), twoLines(issued), scope(BUYER)));
        assertThatThrownBy(() -> inScope(BUYER, () -> {
                    links.link(second, issued, LinkType.SUPERSEDES, null, scope(BUYER));
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.link.drafts_only");
    }

    // ---- density -----------------------------------------------------------------------------

    @Test
    void theGapCheckFlagsAFabricatedGapAndNothingOnADenseSeries() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        List<UUID> ids = List.of(Ids.next(), Ids.next(), Ids.next());
        for (UUID id : ids) {
            inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));
        }
        assertThat(inScope(BUYER, gapCheck::findGaps)).isEmpty();

        // Nobody can do this through the application; only a superuser deleting rows can.
        JdbcTemplate admin = superuserJdbc();
        UUID second = ids.get(1);
        admin.update("delete from kernel.document_state_history where document_id = ?", second);
        admin.update("delete from kernel.document_line where document_id = ?", second);
        admin.update("delete from kernel.document where document_id = ?", second);

        List<GapCheck.Gap> gaps = inScope(BUYER, gapCheck::findGaps);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).expectedCount()).isEqualTo(3);
        assertThat(gaps.get(0).foundCount()).isEqualTo(2);
        assertThat(gaps.get(0).firstMissing()).isEqualTo(2);
    }

    // ---- row-level security ------------------------------------------------------------------

    @Test
    void theRowsOfADocumentFollowTheHeader() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();
        inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));

        // The seller is the counterparty: it reads the order and its lines in its own scope.
        assertThat(inScope(SELLER, () -> documents.findById(id))).isPresent();
        assertThat(inScope(SELLER, () -> documents.findLines(id))).hasSize(2);
        assertThat(inScope(SELLER, () -> documents.findHistory(id))).hasSize(1);

        // A stranger sees nothing of it, and the seller cannot add a line to the buyer's document.
        assertThat(inScope(STRANGER, () -> documents.findById(id))).isEmpty();
        assertThat(inScope(STRANGER, () -> documents.findLines(id))).isEmpty();
        assertThatThrownBy(() -> inScope(SELLER, () -> {
                    documents.saveLines(id, List.of(line(id, 3, "1", "1.00", "0.00")));
                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static SeriesRegistration orderSeries() {
        return SeriesRegistration.forEntity("ORD", BUYER, "M042");
    }

    private static DocumentRecord draftOfType(UUID id, String docTypeCode) {
        return new DocumentRecord(
                id,
                docTypeCode,
                null,
                null,
                null,
                BUYER,
                SELLER,
                null,
                null,
                null,
                "DRAFT",
                null,
                null,
                null,
                USER,
                "LKR",
                null,
                null,
                null,
                null,
                null,
                DocumentOrigin.ONLINE,
                null,
                null);
    }

    private static DocumentRecord draft(UUID id, UUID owner, UUID counterparty) {
        return new DocumentRecord(
                id,
                "ORD",
                null,
                null,
                null,
                owner,
                counterparty,
                null,
                null,
                null,
                "DRAFT",
                null,
                null,
                null,
                USER,
                "LKR",
                null,
                null,
                null,
                null,
                null,
                DocumentOrigin.ONLINE,
                null,
                null);
    }

    private static List<DocumentLineRecord> twoLines(UUID documentId) {
        return List.of(
                line(documentId, 1, "2.000", "100.00", "10.00"), line(documentId, 2, "1.000", "200.00", "20.00"));
    }

    private static DocumentLineRecord line(UUID documentId, int lineNo, String qty, String unitPrice, String tax) {
        BigDecimal quantity = new BigDecimal(qty);
        BigDecimal price = new BigDecimal(unitPrice);
        return new DocumentLineRecord(
                Ids.next(),
                documentId,
                lineNo,
                Ids.next(),
                null,
                "EA",
                quantity,
                price,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("10.000"),
                new BigDecimal(tax),
                quantity.multiply(price).setScale(2),
                null,
                null,
                null);
    }

    private long nextNumber() {
        return superuserJdbc()
                .queryForObject(
                        "select next_number from kernel.numbering_series where prefix = 'M042-ORD'", Long.class);
    }

    private static ScopeContext scope(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
    }

    /** One transaction in the OWN scope of an entity, as the application user, the way a handler runs. */
    private <T> T inScope(UUID entity, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', '', true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    USER.toString(),
                    Ids.next().toString(),
                    entity.toString());
            return work.get();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        /** The test's own document type: an order whose lines must have a positive quantity. */
        static class OrderType implements DocumentTypeHandler {

            volatile boolean failAfterIssue;
            final List<UUID> issued = new ArrayList<>();

            @Override
            public String docTypeCode() {
                return "ORD";
            }

            @Override
            public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
                for (DocumentLineRecord line : lines) {
                    if (line.qty().signum() <= 0) {
                        throw new ProblemException("document.line.qty_invalid");
                    }
                }
            }

            @Override
            public void afterIssue(DocumentRecord issued, List<DocumentLineRecord> lines, ScopeContext ctx) {
                if (failAfterIssue) {
                    throw new IllegalStateException("stock movement failed");
                }
                this.issued.add(issued.id());
            }
        }

        @Bean
        OrderType orderType() {
            return new OrderType();
        }
    }
}
