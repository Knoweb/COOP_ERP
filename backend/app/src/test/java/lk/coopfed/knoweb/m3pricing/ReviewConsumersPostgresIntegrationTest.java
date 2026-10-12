package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistered;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceEntered;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.ReviewRaised;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.internal.list.CreatePriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.PublishPriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.ReviewConsumers;
import lk.coopfed.knoweb.m3pricing.internal.list.SetLinesHandler;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.ReviewItem;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(PinnedClock.class)
class ReviewConsumersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY = UUID.fromString("0190e760-0000-7000-8000-000000000005");
    private final UUID rice = Ids.next();
    private final LocalDate today = PinnedClock.TODAY;

    @Autowired
    CreatePriceListHandler create;

    @Autowired
    SetLinesHandler setLines;

    @Autowired
    PublishPriceListHandler publish;

    @Autowired
    ReviewConsumers consumers;

    @Autowired
    PricingQueries queries;

    private static final UUID TAX_CATEGORY = UUID.fromString("0190e760-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e760-0000-7000-8000-000000000002");

    private lk.coopfed.knoweb.kernel.api.ScopeContext own(UUID entity) {
        return new lk.coopfed.knoweb.kernel.api.ScopeContext(
                USER,
                null,
                entity,
                java.util.List.of(new lk.coopfed.knoweb.kernel.api.Scope(entity, null)),
                new lk.coopfed.knoweb.kernel.api.Scope(entity, null),
                lk.coopfed.knoweb.kernel.api.PolicyClass.OWN,
                java.util.Set.of(),
                java.time.Instant.now(),
                java.util.Locale.ENGLISH,
                null);
    }

    private java.util.List<lk.coopfed.knoweb.testsupport.KernelRecorder.AuditRecord> audit(String eventType) {
        return kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(eventType))
                .toList();
    }

    private <E extends lk.coopfed.knoweb.kernel.api.DomainEvent> java.util.List<E> events(Class<E> type) {
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
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta, base_uom_code, sold_by_weight, tax_category_id)"
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
        admin.execute("truncate table catalogue.sku cascade");
        admin.execute("truncate table pricing.control_price, pricing.mrp_policy");
        admin.execute("truncate table kernel.audit_event");
    }

    private SetLines.Line line(UUID sku, String tier, String price) {
        return new SetLines.Line(sku, "EA", new BigDecimal(tier), new BigDecimal(price));
    }

    @Test
    void batchRegisteredBelowListRaisesReview() {
        UUID list = create.handle(new CreatePriceList("RETAIL", "Shelf 1"), own(SOCIETY));
        setLines.handle(new SetLines(list, List.of(line(rice, "0", "150.00"))), own(SOCIETY));
        publish.handle(new PublishPriceList(list, today), own(SOCIETY));
        kernel.reset();

        UUID batchId = Ids.next();
        consumers.onBatchRegistered(
                new BatchRegistered(
                        batchId, rice, null, FEDERATION, "RC-90", null, null, new BigDecimal("90.00"), false, null),
                own(FEDERATION));

        assertThat(audit("BATCH_MRP_BELOW_LIST")).singleElement().satisfies(r -> {
            assertThat(r.subject().id()).isEqualTo(rice);
            Map<?, ?> details = (Map<?, ?>) r.after();
            assertThat(details.get("batchNo").toString()).isEqualTo("RC-90");
            assertThat(new BigDecimal(details.get("printedMrp").toString()))
                    .isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(new BigDecimal(details.get("listPrice").toString()))
                    .isEqualByComparingTo(new BigDecimal("150.00"));
        });

        assertThat(events(ReviewRaised.class)).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("BATCH_MRP_BELOW_LIST");
            assertThat(e.ownerEntityId()).isEqualTo(SOCIETY);
            assertThat(e.skuId()).isEqualTo(rice);
            assertThat(e.details()).isEqualTo("RC-90");
        });

        // Compliant batch => no review
        kernel.reset();
        consumers.onBatchRegistered(
                new BatchRegistered(
                        Ids.next(),
                        rice,
                        null,
                        FEDERATION,
                        "RC-160",
                        null,
                        null,
                        new BigDecimal("160.00"),
                        false,
                        null),
                own(FEDERATION));
        assertThat(audit("BATCH_MRP_BELOW_LIST")).isEmpty();

        // Replay the same batch => duplicate review is NOT created because of replay protection
        kernel.reset();
        consumers.onBatchRegistered(
                new BatchRegistered(
                        batchId, rice, null, FEDERATION, "RC-90", null, null, new BigDecimal("90.00"), false, null),
                own(FEDERATION));
        assertThat(audit("BATCH_MRP_BELOW_LIST")).isEmpty();
    }

    @Test
    void controlPriceEnteredBelowListRaisesReview() {
        UUID list = create.handle(new CreatePriceList("RETAIL", "Shelf 2"), own(SOCIETY));
        setLines.handle(new SetLines(list, List.of(line(rice, "0", "150.00"))), own(SOCIETY));
        publish.handle(new PublishPriceList(list, today), own(SOCIETY));
        kernel.reset();

        UUID cp = Ids.next();
        consumers.onControlPriceEntered(
                new ControlPriceEntered(cp, rice, new BigDecimal("100.00"), "EA", today, null, "GAZ-01", null),
                own(FEDERATION));

        assertThat(audit("LIST_ABOVE_CEILING_AT_GAZETTE")).singleElement().satisfies(r -> {
            assertThat(r.subject().id()).isEqualTo(rice);
            Map<?, ?> details = (Map<?, ?>) r.after();
            assertThat(details.get("gazetteReference").toString()).isEqualTo("GAZ-01");
            assertThat(new BigDecimal(details.get("ceilingPrice").toString()))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(new BigDecimal(details.get("listPrice").toString()))
                    .isEqualByComparingTo(new BigDecimal("150.00"));
        });

        assertThat(events(ReviewRaised.class)).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("LIST_ABOVE_CEILING_AT_GAZETTE");
            assertThat(e.ownerEntityId()).isEqualTo(SOCIETY);
            assertThat(e.skuId()).isEqualTo(rice);
            assertThat(e.details()).isEqualTo("GAZ-01");
        });

        // Compliant gazette => no review
        kernel.reset();
        consumers.onControlPriceEntered(
                new ControlPriceEntered(Ids.next(), rice, new BigDecimal("160.00"), "EA", today, null, "GAZ-02", null),
                own(FEDERATION));
        assertThat(audit("LIST_ABOVE_CEILING_AT_GAZETTE")).isEmpty();

        // Replay the same event => no duplicate review
        kernel.reset();
        consumers.onControlPriceEntered(
                new ControlPriceEntered(cp, rice, new BigDecimal("100.00"), "EA", today, null, "GAZ-01", null),
                own(FEDERATION));
        assertThat(audit("LIST_ABOVE_CEILING_AT_GAZETTE")).isEmpty();
    }

    @Test
    void reviewsQueryEnforcesScopeAndFields() {
        UUID list = create.handle(new CreatePriceList("RETAIL", "Shelf 3"), own(SOCIETY));
        setLines.handle(new SetLines(list, List.of(line(rice, "0", "150.00"))), own(SOCIETY));
        publish.handle(new PublishPriceList(list, today), own(SOCIETY));

        consumers.onBatchRegistered(
                new BatchRegistered(
                        Ids.next(), rice, null, FEDERATION, "RC-90", null, null, new BigDecimal("90.00"), false, null),
                own(FEDERATION));

        List<ReviewItem> reviews = queries.listReviews(null, null, own(SOCIETY));
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).kind()).isEqualTo("BATCH_MRP_BELOW_LIST");
        assertThat(reviews.get(0).skuId()).isEqualTo(rice);
        assertThat(reviews.get(0).severity()).isEqualTo("REVIEW");

        assertThat(queries.listReviews("BATCH_MRP_BELOW_LIST", null, own(SOCIETY)))
                .hasSize(1);
        assertThat(queries.listReviews("TRADE_ABOVE_MRP", null, own(SOCIETY))).isEmpty();

        // Unrelated entity cannot see them
        assertThat(queries.listReviews(null, null, own(FEDERATION))).isEmpty();
    }
}
