package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistered;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceEntered;
import lk.coopfed.knoweb.m3pricing.api.ReviewCheckCompleted;
import lk.coopfed.knoweb.m3pricing.api.ReviewRaised;
import lk.coopfed.knoweb.m3pricing.internal.review.PricingReviewJob;
import lk.coopfed.knoweb.m3pricing.internal.review.ReviewConsumers;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.ReviewItem;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The gazette and batch-MRP reviews (23A section 7.1) as wave 4 rebuilt them (M3R-04, M3R-05,
 * M3R-07, M3R-08, CR-19A-14): the consumers record the event in the scope the dispatcher gives
 * them; PricingReviewJob raises each review in its list owner's scope. The price lists are written
 * by the superuser, as published versions on the dates each case needs.
 */
@Import(PinnedClock.class)
class ReviewConsumersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY = UUID.fromString("0190e760-0000-7000-8000-000000000005");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e760-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e760-0000-7000-8000-000000000002");

    private final UUID rice = Ids.next();
    private final LocalDate today = PinnedClock.TODAY;

    @Autowired
    ReviewConsumers consumers;

    @Autowired
    PricingReviewJob job;

    @Autowired
    PricingQueries queries;

    @Autowired
    AuditReviewReader auditReviews;

    private static ScopeContext scope(UUID entity, PolicyClass policyClass) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(active),
                active,
                policyClass,
                Set.of(),
                Instant.now(),
                Locale.ENGLISH,
                null);
    }

    private static ScopeContext own(UUID entity) {
        return scope(entity, PolicyClass.OWN);
    }

    private static final JobExecution EXECUTION = new JobExecution() {
        private final UUID runId = UUID.randomUUID();

        @Override
        public UUID runId() {
            return runId;
        }

        @Override
        public void itemsProcessed(int count) {}

        @Override
        public Optional<ScopeContext> systemScope() {
            return Optional.empty();
        }
    };

    private List<KernelRecorder.AuditRecord> audit(String eventType) {
        return kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(eventType))
                .toList();
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3LIST', 'M3 list tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, tax_category_id)"
                        + " values (?, 'RC', ?, 'SHARED', 'Rice', 'Rice', 'Rice', 'EA', false, ?)",
                rice,
                FEDERATION,
                TAX_CATEGORY);
        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, district,
                    financial_year_start_month, default_language, status)
                values (?, 'M3SOC', 'MPCS', 'M3 test society', 'Kurunegala', 1, 'si', 'ACTIVE')
                on conflict (entity_id) do nothing
                """,
                SOCIETY);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.price_list_line, pricing.price_list cascade");
        admin.execute("truncate table pricing.review_trigger");
        admin.execute("truncate table catalogue.sku cascade");
        admin.execute("truncate table pricing.control_price, pricing.mrp_policy");
        admin.execute("truncate table kernel.audit_event");
    }

    /** A list version with lines (unit, price), as the superuser writes it. */
    private UUID list(
            UUID owner, String kind, UUID root, int version, String status, LocalDate applyFrom, Object... lines) {
        UUID id = Ids.next();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into pricing.price_list (price_list_id, owner_entity_id, kind, name, version, root_price_list_id,"
                        + " status, apply_from) values (?, ?, ?, 'List', ?, ?, ?, ?)",
                id,
                owner,
                kind,
                version,
                root == null ? id : root,
                status,
                Date.valueOf(applyFrom));
        for (int i = 0; i < lines.length; i += 2) {
            admin.update(
                    "insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price,"
                            + " effective_from, owner_entity_id) values (?, ?, ?, ?, 0, ?, ?, ?)",
                    Ids.next(),
                    id,
                    rice,
                    lines[i],
                    new BigDecimal((String) lines[i + 1]),
                    Date.valueOf(applyFrom),
                    owner);
        }
        return id;
    }

    private BatchRegistered batch(UUID batchId, String batchNo, String mrp) {
        return new BatchRegistered(
                batchId, rice, null, FEDERATION, batchNo, null, null, new BigDecimal(mrp), false, null);
    }

    private ControlPriceEntered gazette(UUID controlPriceId, String ceiling, String unit, String reference) {
        return new ControlPriceEntered(
                controlPriceId, rice, new BigDecimal(ceiling), unit, today, null, reference, null);
    }

    private int triggers() {
        return superuserJdbc().queryForObject("select count(*) from pricing.review_trigger", Integer.class);
    }

    @Test
    void aBatchBelowTheListIsRecordedThenRaisedByTheJobInTheListOwnersScope() {
        list(SOCIETY, "RETAIL", null, 1, "PUBLISHED", today, "EA", "150.00");

        UUID batchId = Ids.next();
        consumers.onBatchRegistered(batch(batchId, "RC-90", "90.00"), own(FEDERATION));

        // The consumer only records the work, in the event owner's name: no review yet.
        assertThat(triggers()).isEqualTo(1);
        assertThat(superuserJdbc().queryForObject("select owner_entity_id from pricing.review_trigger", UUID.class))
                .isEqualTo(FEDERATION);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        assertThat(job.raiseReviews(EXECUTION)).isEqualTo(1);

        assertThat(audit("BATCH_MRP_BELOW_LIST")).singleElement().satisfies(r -> {
            assertThat(r.subject().id()).isEqualTo(rice);
            assertThat(r.scope().entityId()).isEqualTo(SOCIETY);
            Map<?, ?> details = (Map<?, ?>) r.after();
            assertThat(details.get("batchNo")).isEqualTo("RC-90");
            assertThat(details.get("uomCode")).isEqualTo("EA");
            assertThat(details.get("reviewKey")).isEqualTo(batchId + ":RETAIL");
            assertThat((BigDecimal) details.get("printedMrp")).isEqualByComparingTo("90.00");
            assertThat((BigDecimal) details.get("listPrice")).isEqualByComparingTo("150.00");
        });
        assertThat(events(ReviewRaised.class)).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("BATCH_MRP_BELOW_LIST");
            assertThat(e.ownerEntityId()).isEqualTo(SOCIETY);
            assertThat(e.skuId()).isEqualTo(rice);
            assertThat(e.details()).isEqualTo("RC-90");
        });
        // The trigger is checked in its own owner's scope.
        assertThat(audit("PRICING_REVIEW_CHECKED"))
                .singleElement()
                .satisfies(r -> assertThat(r.scope().entityId()).isEqualTo(FEDERATION));
        assertThat(events(ReviewCheckCompleted.class)).singleElement().satisfies(e -> {
            assertThat(e.ownerEntityId()).isEqualTo(FEDERATION);
            assertThat(e.reviewsRaised()).isEqualTo(1);
        });

        // A replay of the event records nothing, and the next run finds nothing to do.
        kernel.reset();
        consumers.onBatchRegistered(batch(batchId, "RC-90", "90.00"), own(FEDERATION));
        assertThat(triggers()).isEqualTo(1);
        assertThat(job.raiseReviews(EXECUTION)).isZero();
        assertThat(kernel.committedAudit()).isEmpty();

        // A compliant batch is checked and raises nothing.
        kernel.reset();
        consumers.onBatchRegistered(batch(Ids.next(), "RC-160", "160.00"), own(FEDERATION));
        assertThat(job.raiseReviews(EXECUTION)).isZero();
        assertThat(audit("BATCH_MRP_BELOW_LIST")).isEmpty();
        assertThat(events(ReviewCheckCompleted.class)).singleElement().satisfies(e -> assertThat(e.reviewsRaised())
                .isZero());
    }

    @Test
    void aGazetteIsComparedWithTheLineOfTheSameUnitOnly() {
        // Rice per each at 210 and per pack of five at 1,000 (M3R-04).
        list(SOCIETY, "RETAIL", null, 1, "PUBLISHED", today, "EA", "210.00", "PK5", "1000.00");

        consumers.onControlPriceEntered(gazette(Ids.next(), "220.00", "EA", "GAZ-01"), own(FEDERATION));
        assertThat(job.raiseReviews(EXECUTION)).isZero();
        assertThat(audit("LIST_ABOVE_CEILING_AT_GAZETTE")).isEmpty();

        kernel.reset();
        consumers.onControlPriceEntered(gazette(Ids.next(), "200.00", "EA", "GAZ-02"), own(FEDERATION));
        assertThat(job.raiseReviews(EXECUTION)).isEqualTo(1);
        assertThat(audit("LIST_ABOVE_CEILING_AT_GAZETTE")).singleElement().satisfies(r -> {
            Map<?, ?> details = (Map<?, ?>) r.after();
            assertThat(details.get("uomCode")).isEqualTo("EA");
            assertThat(details.get("gazetteReference")).isEqualTo("GAZ-02");
            assertThat((BigDecimal) details.get("listPrice")).isEqualByComparingTo("210.00");
            assertThat((BigDecimal) details.get("ceilingPrice")).isEqualByComparingTo("200.00");
        });
    }

    @Test
    void theVersionInForceTodayIsComparedEvenWhenAlreadySuperseded() {
        // v1 (150) is in force until v2 (85) applies next week; v1 is already SUPERSEDED (M3R-04).
        UUID v1 = list(SOCIETY, "RETAIL", null, 1, "SUPERSEDED", today, "EA", "150.00");
        list(SOCIETY, "RETAIL", v1, 2, "PUBLISHED", today.plusDays(7), "EA", "85.00");

        consumers.onBatchRegistered(batch(Ids.next(), "RC-90", "90.00"), own(FEDERATION));
        assertThat(job.raiseReviews(EXECUTION)).isEqualTo(1);
        assertThat(audit("BATCH_MRP_BELOW_LIST")).singleElement().satisfies(r -> assertThat(
                        (BigDecimal) ((Map<?, ?>) r.after()).get("listPrice"))
                .isEqualByComparingTo("150.00"));
    }

    @Test
    void eachListKindOfAnOwnerAboveTheCeilingGetsItsOwnReview() {
        // M3R-05: the Federation's ADVISORY and TRADE lists both above one gazette.
        list(FEDERATION, "ADVISORY", null, 1, "PUBLISHED", today, "EA", "150.00");
        list(FEDERATION, "TRADE", null, 1, "PUBLISHED", today, "EA", "140.00");

        UUID controlPrice = Ids.next();
        consumers.onControlPriceEntered(gazette(controlPrice, "100.00", "EA", "GAZ-03"), own(FEDERATION));
        assertThat(job.raiseReviews(EXECUTION)).isEqualTo(2);

        assertThat(audit("LIST_ABOVE_CEILING_AT_GAZETTE"))
                .extracting(r -> (Object) ((Map<?, ?>) r.after()).get("listKind"))
                .containsExactlyInAnyOrder("ADVISORY", "TRADE");
        assertThat(events(ReviewRaised.class)).hasSize(2);
    }

    @Test
    void reviewsAreListedWithTheirOwnerSkuAndStatusInTheCallersScope() {
        list(SOCIETY, "RETAIL", null, 1, "PUBLISHED", today, "EA", "150.00");
        consumers.onBatchRegistered(batch(Ids.next(), "RC-90", "90.00"), own(FEDERATION));
        job.raiseReviews(EXECUTION);

        List<ReviewItem> reviews = queries.listReviews(null, null, own(SOCIETY));
        assertThat(reviews).singleElement().satisfies(review -> {
            assertThat(review.kind()).isEqualTo("BATCH_MRP_BELOW_LIST");
            assertThat(review.severity()).isEqualTo("REVIEW");
            assertThat(review.status()).isEqualTo("OPEN");
            assertThat(review.ownerEntityId()).isEqualTo(SOCIETY);
            assertThat(review.skuId()).isEqualTo(rice);
            assertThat(review.skuCode()).isEqualTo("RC");
            assertThat(review.skuName()).isEqualTo("Rice");
            assertThat(review.details()).containsEntry("batchNo", "RC-90");
        });
        assertThat(queries.listReviews("BATCH_MRP_BELOW_LIST", "OPEN", own(SOCIETY)))
                .hasSize(1);
        assertThat(queries.listReviews(null, "SETTLED", own(SOCIETY))).isEmpty();
        assertThat(queries.listReviews("TRADE_ABOVE_MRP", null, own(SOCIETY))).isEmpty();

        // Another entity reads none; the Federation's view reads it with its owner.
        assertThat(queries.listReviews(null, null, own(FEDERATION))).isEmpty();
        assertThat(queries.listReviews(null, null, scope(FEDERATION, PolicyClass.FEDERATION_VIEW)))
                .singleElement()
                .satisfies(review -> assertThat(review.ownerEntityId()).isEqualTo(SOCIETY));
    }

    @Test
    void theReviewFunctionsRefuseAPartyScope() {
        // M3R-10: only OWN and FEDERATION_VIEW may ask (kernel V0093).
        ScopeContext party = scope(SOCIETY, PolicyClass.PARTY);
        assertThatThrownBy(() -> auditReviewsList(party)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> auditReviews.reviewExists("BATCH_MRP_BELOW_LIST", rice, null, null, party))
                .isInstanceOf(DataAccessException.class);
    }

    private Object auditReviewsList(ScopeContext scope) {
        return auditReviews.listReviews(Set.of("BATCH_MRP_BELOW_LIST"), 10, scope);
    }
}
