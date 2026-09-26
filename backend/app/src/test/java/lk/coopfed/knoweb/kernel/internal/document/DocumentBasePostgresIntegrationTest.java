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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentIssued;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentLinkRecord;
import lk.coopfed.knoweb.kernel.api.DocumentLinks;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentStateHistoryRecord;
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
    private static final UUID LOCATION = UUID.fromString("0190d000-0000-7000-8000-000000000030");
    private static final UUID POSITION = UUID.fromString("0190d000-0000-7000-8000-000000000031");

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
            documents.addStateTransition(transition(id, "ISSUED", "ACCEPTED"), scope(BUYER));
            return null;
        });
        assertThat(inScope(BUYER, () -> documents.findById(id).orElseThrow().status()))
                .isEqualTo("ACCEPTED");
        assertThat(inScope(BUYER, () -> documents.findHistory(id))).hasSize(2);
    }

    @Test
    void noLineJoinsAnIssuedDocument() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();
        inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));

        // The owner is refused by the policy ...
        assertThatThrownBy(() -> inScope(BUYER, () -> {
                    documents.saveLines(id, List.of(line(id, 3, "1", "1.00", "0.00")));
                    return null;
                }))
                .isInstanceOf(DataAccessException.class);

        // ... and the trigger refuses whoever bypasses the policies, the superuser included.
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "insert into kernel.document_line (document_line_id, document_id, line_no, qty)"
                                        + " values (?, ?, 3, 1)",
                                Ids.next(),
                                id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("document.immutable");

        assertThat(inScope(BUYER, () -> documents.findLines(id))).hasSize(2);
    }

    @Test
    void aStateTransitionIsCompareAndSetAndAudited() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();
        DocumentRecord issued =
                inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));
        kernel.reset();

        inScope(BUYER, () -> {
            documents.addStateTransition(transition(id, "ISSUED", "ACCEPTED"), scope(BUYER));
            return null;
        });
        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("DOCUMENT_STATUS_CHANGED");
        kernel.reset();

        // A transition from a status the document has left changes nothing and is refused.
        assertThatThrownBy(() -> inScope(BUYER, () -> {
                    documents.addStateTransition(transition(id, "ISSUED", "CANCELLED"), scope(BUYER));
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.status_conflict");
        assertThat(inScope(BUYER, () -> documents.findById(id).orElseThrow().status()))
                .isEqualTo("ACCEPTED");
        assertThat(inScope(BUYER, () -> documents.findHistory(id))).hasSize(2);
        assertThat(kernel.committedAudit()).isEmpty();

        // save() is not a way to change the status of an issued document ...
        assertThatThrownBy(() -> inScope(BUYER, () -> documents.save(issued.withStatus("CANCELLED"))))
                .isInstanceOf(ProblemException.class);

        // ... nor a way to issue a draft around the protocol.
        UUID draftId = Ids.next();
        DocumentRecord numberedByHand = new DocumentRecord(
                draftId,
                "ORD",
                issued.seriesId(),
                99L,
                "M042-ORD-0000099",
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
        assertThatThrownBy(() -> inScope(BUYER, () -> documents.save(numberedByHand)))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.issued_fields_reserved");
    }

    @Test
    void aStoredDraftIsIssuedFromItsStoredHeaderAndLines() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();
        inScope(BUYER, () -> documents.save(draft(id, BUYER, SELLER)));
        inScope(BUYER, () -> {
            documents.saveLines(id, List.of(line(id, 1, "1.000", "50.00", "5.00")));
            return null;
        });

        // The caller's copy names another counterparty and other lines: the store wins.
        DocumentRecord issued =
                inScope(BUYER, () -> issuance.issue(draft(id, BUYER, STRANGER), twoLines(id), scope(BUYER)));

        assertThat(issued.counterpartyEntityId()).isEqualTo(SELLER);
        assertThat(issued.grossAmount()).isEqualByComparingTo("55.00");
        assertThat(inScope(BUYER, () -> documents.findLines(id))).hasSize(1);
        DocumentRecord stored = inScope(BUYER, () -> documents.findById(id).orElseThrow());
        assertThat(ContentHash.of(stored.withContentHash(null), inScope(BUYER, () -> documents.findLines(id))))
                .isEqualTo(issued.contentHash());
    }

    @Test
    void theHashIsOverTheStoredScaleOfEveryDecimal() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();
        List<DocumentLineRecord> lines = List.of(line(id, 1, "3.0005", "33.33333", "0.005"));

        DocumentRecord issued = inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), lines, scope(BUYER)));

        DocumentRecord stored = inScope(BUYER, () -> documents.findById(id).orElseThrow());
        List<DocumentLineRecord> storedLines = inScope(BUYER, () -> documents.findLines(id));
        assertThat(storedLines.get(0).unitPrice()).isEqualByComparingTo("33.3333");
        assertThat(ContentHash.of(stored.withContentHash(null), storedLines)).isEqualTo(issued.contentHash());
    }

    @Test
    void centralIssuanceRefusesWhatATillNumbers() {
        // A series a device holds ...
        UUID seriesId = inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        inScope(BUYER, () -> {
            numbering.holderChange(List.of(seriesId), DEVICE, scope(BUYER));
            return null;
        });
        UUID id = Ids.next();
        assertThatThrownBy(() ->
                        inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.series_device_held");
        assertThat(nextNumber()).isEqualTo(1L);

        // ... a TILL_POSITION series ...
        inScope(
                BUYER,
                () -> numbering.registerSeries(
                        SeriesRegistration.forTillPosition("RCT", BUYER, LOCATION, POSITION, "M042", "S01", 1, null),
                        scope(BUYER)));
        UUID receipt = Ids.next();
        DocumentRecord tillDraft = draft(receipt, "RCT", BUYER, null, LOCATION, POSITION, DocumentOrigin.ONLINE);
        assertThatThrownBy(() -> inScope(BUYER, () -> issuance.issue(tillDraft, twoLines(receipt), scope(BUYER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.series_device_held");

        // ... and a draft that says it was issued offline.
        UUID offline = Ids.next();
        DocumentRecord offlineDraft = draft(offline, "ORD", BUYER, SELLER, null, null, DocumentOrigin.OFFLINE);
        assertThatThrownBy(() -> inScope(BUYER, () -> issuance.issue(offlineDraft, twoLines(offline), scope(BUYER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.series_device_held");

        assertThat(superuserJdbc().queryForObject("select count(*) from kernel.document", Long.class))
                .isZero();
    }

    @Test
    void aShopScopedSessionIssuesFromTheEntitySeries() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();

        DocumentRecord issued = inScopeAt(
                BUYER,
                LOCATION,
                () -> issuance.issue(
                        draft(id, "ORD", BUYER, SELLER, LOCATION, null, DocumentOrigin.ONLINE),
                        twoLines(id),
                        scopeAt(BUYER, LOCATION)));

        assertThat(issued.docNumberDisplay()).isEqualTo("M042-ORD-0000001");
        assertThat(inScopeAt(BUYER, LOCATION, () -> documents.findById(id))).isPresent();
    }

    @Test
    void aCounterpartyAtALocationReadsTheDocument() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID id = Ids.next();
        inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));

        assertThat(inScopeAt(SELLER, LOCATION, () -> documents.findById(id))).isPresent();
        assertThat(inScopeAt(SELLER, LOCATION, () -> documents.findLines(id))).hasSize(2);
        assertThat(inScopeAt(STRANGER, LOCATION, () -> documents.findById(id))).isEmpty();
    }

    @Test
    void concurrentSettlementsOfOneOriginalSeeEachOther() throws Exception {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID original = Ids.next();
        UUID first = Ids.next();
        UUID second = Ids.next();
        for (UUID id : List.of(original, first, second)) {
            inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));
        }

        // The first settlement holds its transaction open after it linked 300.00; the second,
        // for 200.00, must wait for it and then see 500.00 against a gross of 430.00.
        CountDownLatch firstLinked = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> inScope(BUYER, () -> {
                links.link(first, original, LinkType.SETTLES, new BigDecimal("300.00"), scope(BUYER));
                firstLinked.countDown();
                try {
                    // Long enough for the second to have queued behind the lock, not to have run.
                    secondFinished.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(firstLinked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> waiter = pool.submit(() -> inScope(BUYER, () -> {
                links.link(second, original, LinkType.SETTLES, new BigDecimal("200.00"), scope(BUYER));
                return null;
            }));

            holder.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> waiter.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ProblemException.class)
                    .hasMessageContaining("document.link.exceeds_balance");
            secondFinished.countDown();
        } finally {
            pool.shutdownNow();
        }

        assertThat(inScope(BUYER, () -> documents.findLinks(original))).hasSize(1);
    }

    @Test
    void aSecondReversalIsRefusedByTheDatabaseToo() {
        inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
        UUID original = Ids.next();
        UUID reversal = Ids.next();
        UUID secondReversal = Ids.next();
        for (UUID id : List.of(original, reversal, secondReversal)) {
            inScope(BUYER, () -> issuance.issue(draft(id, BUYER, SELLER), twoLines(id), scope(BUYER)));
        }
        inScope(BUYER, () -> {
            links.link(reversal, original, LinkType.REVERSES, null, scope(BUYER));
            return null;
        });

        // Straight at the repository, past the check DocumentLinks makes: the index decides.
        assertThatThrownBy(() -> inScope(BUYER, () -> {
                    documents.addLink(new DocumentLinkRecord(
                            secondReversal, original, LinkType.REVERSES, null, Instant.now(), USER));
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("document.link.reversed_already");
    }

    @Test
    void concurrentRegistrationsOfOneSeriesGiveOneSeries() throws Exception {
        int registrars = 4;
        ExecutorService pool = Executors.newFixedThreadPool(registrars);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();
        try {
            for (int t = 0; t < registrars; t++) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));
                }));
            }
            start.countDown();
            Set<UUID> ids = new java.util.HashSet<>();
            for (Future<UUID> result : results) {
                ids.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(superuserJdbc().queryForObject("select count(*) from kernel.numbering_series", Long.class))
                .isEqualTo(1L);
        assertThat(kernel.committedAudit().stream().filter(r -> "SERIES_REGISTERED".equals(r.eventType())))
                .hasSize(1);
    }

    @Test
    void theGapCheckMeasuresASeriesWhoseCounterIsBehindItsDocuments() {
        UUID seriesId = inScope(BUYER, () -> numbering.registerSeries(orderSeries(), scope(BUYER)));

        // A document numbered 3 arrived (as ingestion will write it) while the counter is at 1.
        superuserJdbc()
                .update(
                        """
                        insert into kernel.document (document_id, doc_type_code, series_id, doc_number,
                            doc_number_display, owner_entity_id, status, issued_at, business_date, content_hash)
                        values (?, 'ORD', ?, 3, 'M042-ORD-0000003', ?, 'ISSUED', now(), current_date, repeat('0', 64))
                        """,
                        Ids.next(),
                        seriesId,
                        BUYER);

        List<GapCheck.Gap> gaps = inScope(BUYER, gapCheck::findGaps);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).expectedCount()).isEqualTo(3);
        assertThat(gaps.get(0).foundCount()).isEqualTo(1);
        assertThat(gaps.get(0).firstMissing()).isEqualTo(1);
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
        return draft(id, docTypeCode, BUYER, SELLER, null, null, DocumentOrigin.ONLINE);
    }

    private static DocumentRecord draft(UUID id, UUID owner, UUID counterparty) {
        return draft(id, "ORD", owner, counterparty, null, null, DocumentOrigin.ONLINE);
    }

    private static DocumentRecord draft(
            UUID id,
            String docTypeCode,
            UUID owner,
            UUID counterparty,
            UUID locationId,
            UUID tillPositionId,
            DocumentOrigin origin) {
        return new DocumentRecord(
                id,
                docTypeCode,
                null,
                null,
                null,
                owner,
                counterparty,
                locationId,
                tillPositionId,
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
                origin,
                null,
                null);
    }

    private static DocumentStateHistoryRecord transition(UUID documentId, String from, String to) {
        return new DocumentStateHistoryRecord(
                Ids.next(), documentId, from, to, Instant.now(), null, USER, null, null, null);
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
                quantity.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP),
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
        return scopeAt(entity, null);
    }

    private static ScopeContext scopeAt(UUID entity, UUID location) {
        Scope active = new Scope(entity, location);
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
        return inScopeAt(entity, null, work);
    }

    /** The same, for a session scoped to one location of the entity (a shop manager at a shop). */
    private <T> T inScopeAt(UUID entity, UUID location, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', ?, true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    USER.toString(),
                    Ids.next().toString(),
                    entity.toString(),
                    location == null ? "" : location.toString());
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

        /** A till receipt, so that the test can register a TILL_POSITION series and try to issue from it. */
        static class ReceiptType implements DocumentTypeHandler {

            @Override
            public String docTypeCode() {
                return "RCT";
            }
        }

        @Bean
        ReceiptType receiptType() {
            return new ReceiptType();
        }
    }
}
