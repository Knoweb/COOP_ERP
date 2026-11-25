package lk.coopfed.knoweb.m3pricing.internal.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.EnterControlPrice;
import lk.coopfed.knoweb.m3pricing.api.LabelJobFailed;
import lk.coopfed.knoweb.m3pricing.api.LabelJobRendered;
import lk.coopfed.knoweb.m3pricing.api.LabelJobRequested;
import lk.coopfed.knoweb.m3pricing.api.PrintShelfLabels;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.EnterControlPriceHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.CreatePriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.PublishPriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.SetLinesHandler;
import lk.coopfed.knoweb.m3pricing.query.LabelJobView;
import lk.coopfed.knoweb.m3pricing.query.LabelRefusal;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** PRC-03: queue, render and refuse shelf labels against the print-date control price. */
@Import({PinnedClock.class, ShelfLabelPostgresIntegrationTest.RendererTestConfig.class})
class ShelfLabelPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY = UUID.fromString("0190e903-0000-7000-8000-000000000005");
    private static final UUID OTHER = UUID.fromString("0190e903-0000-7000-8000-000000000006");
    private static final UUID SHOP = UUID.fromString("0190e903-0000-7000-8000-000000000105");
    private static final UUID OTHER_SHOP = UUID.fromString("0190e903-0000-7000-8000-000000000106");
    private static final UUID USER = UUID.fromString("0190e903-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e903-0000-7000-8000-000000000100");
    private static final String TEMPLATE_KEY = "pricing.label_template";

    @Autowired
    PrintShelfLabelsHandler request;

    @Autowired
    LabelJobWorker worker;

    @Autowired
    RecordingRenderer renderer;

    @Autowired
    PricingQueries queries;

    @Autowired
    CreatePriceListHandler create;

    @Autowired
    SetLinesHandler setLines;

    @Autowired
    PublishPriceListHandler publish;

    @Autowired
    EnterControlPriceHandler enterControlPrice;

    @Autowired
    JdbcConfigRegistry config;

    private final UUID rice = Ids.next();
    private final UUID sugar = Ids.next();
    private final UUID unlisted = Ids.next();

    @BeforeEach
    void arrange() {
        clean();
        renderer.reset();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3LABEL', 'M3 label tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        insertEntity(admin, SOCIETY, "M3LSOC", "Label society", "ta");
        insertEntity(admin, OTHER, "M3LOTH", "Other society", "en");
        insertLocation(admin, SHOP, SOCIETY, "M3LSHOP", "Main shop", "ta");
        insertLocation(admin, OTHER_SHOP, OTHER, "M3LOTHER", "Other shop", "en");
        insertSku(admin, rice, "M3L-RICE", "සහල්", "அரிசி", "Rice");
        insertSku(admin, sugar, "M3L-SUGAR", "සීනි", "சீனி", "Sugar");
        insertSku(admin, unlisted, "M3L-OTHER", "වෙනත්", "மற்றவை", "Other");

        UUID list = create.handle(new CreatePriceList("RETAIL", "Society shelf prices"), own(SOCIETY));
        setLines.handle(
                new SetLines(
                        list,
                        List.of(
                                new SetLines.Line(rice, "EA", BigDecimal.ZERO, new BigDecimal("200.00")),
                                new SetLines.Line(sugar, "EA", BigDecimal.ZERO, new BigDecimal("240.00")))),
                own(SOCIETY));
        publish.handle(new PublishPriceList(list, PinnedClock.TODAY), own(SOCIETY));
        enterControlPrice.handle(
                new EnterControlPrice(sugar, new BigDecimal("220.00"), "EA", PinnedClock.TODAY, null, "2492/29"),
                own(FEDERATION));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.label_job, pricing.price_list_line, pricing.price_list cascade");
        admin.execute("truncate table pricing.control_price");
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
        admin.update("delete from kernel.config_value where key = ?", TEMPLATE_KEY);
        config.invalidate(TEMPLATE_KEY);
    }

    @Test
    void aQueuedShelfListRendersEligibleLabelsAndRecordsTheControlPriceRefusal() {
        UUID jobId = request.handle(new PrintShelfLabels(SHOP, List.of(rice, sugar)), own(SOCIETY));

        assertThat(queries.labelJob(jobId, own(SOCIETY))).get().satisfies(job -> {
            assertThat(job.status()).isEqualTo("QUEUED");
            assertThat(job.requestedCount()).isEqualTo(2);
            assertThat(job.refused()).isEmpty();
        });
        assertThat(audit("LABEL_JOB_CREATED")).singleElement().satisfies(a -> {
            assertThat(a.subject().id()).isEqualTo(jobId);
            assertThat(((Map<?, ?>) a.after()).get("requestedCount")).isEqualTo(2);
        });
        assertThat(events(LabelJobRequested.class)).containsExactly(new LabelJobRequested(jobId, SHOP, 2));
        kernel.reset();

        assertThat(worker.renderQueued(execution())).isEqualTo(1);

        assertThat(renderer.templateId).isEqualTo("default");
        assertThat(renderer.language).isEqualTo(Locale.forLanguageTag("ta"));
        assertThat(renderer.labels()).singleElement().satisfies(label -> {
            assertThat(label.get("code")).isEqualTo("M3L-RICE");
            assertThat(label.get("nameSi")).isEqualTo("සහල්");
            assertThat(label.get("nameTa")).isEqualTo("அரிசி");
            assertThat(label.get("nameEn")).isEqualTo("Rice");
        });

        LabelJobView rendered = queries.labelJob(jobId, own(SOCIETY)).orElseThrow();
        assertThat(rendered.status()).isEqualTo("RENDERED");
        assertThat(rendered.renderedCount()).isEqualTo(1);
        assertThat(rendered.objectKey()).isEqualTo("reports/" + SOCIETY + "/labels.pdf");
        assertThat(rendered.refused()).singleElement().satisfies(refusal -> {
            assertThat(refusal.skuId()).isEqualTo(sugar);
            assertThat(refusal.reason()).isEqualTo(LabelRefusal.ABOVE_CONTROL_PRICE);
            assertThat(refusal.listPrice()).isEqualByComparingTo("240.00");
            assertThat(refusal.ceilingPrice()).isEqualByComparingTo("220.00");
            assertThat(refusal.ceilingReference()).isEqualTo("2492/29");
        });
        assertThat(audit("LABEL_JOB_RENDERED")).singleElement();
        assertThat(events(LabelJobRendered.class)).containsExactly(new LabelJobRendered(jobId, SHOP, 1));

        kernel.reset();
        assertThat(worker.renderQueued(execution()))
                .as("a terminal job is not printed twice")
                .isZero();
        assertThat(renderer.calls).isEqualTo(1);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void anEntireShelfListAboveTheCeilingStillRendersAnEmptyResultWithEveryRefusal() {
        UUID jobId = request.handle(new PrintShelfLabels(SHOP, List.of(sugar)), own(SOCIETY));
        kernel.reset();
        assertThat(worker.renderQueued(execution())).isEqualTo(1);

        assertThat(renderer.labels()).isEmpty();
        assertThat(renderer.data.get("empty")).isNotNull();
        assertThat(queries.labelJob(jobId, own(SOCIETY))).get().satisfies(job -> {
            assertThat(job.status()).isEqualTo("RENDERED");
            assertThat(job.renderedCount()).isZero();
            assertThat(job.refused())
                    .singleElement()
                    .extracting(LabelRefusal::skuId)
                    .isEqualTo(sugar);
        });
        assertThat(events(LabelJobRendered.class)).containsExactly(new LabelJobRendered(jobId, SHOP, 0));
    }

    @Test
    void aRendererFailureSettlesTheJobFailedAndPublishesTheRequiredFailureOutcome() {
        UUID jobId = request.handle(new PrintShelfLabels(SHOP, List.of(rice)), own(SOCIETY));
        kernel.reset();

        renderer.failure = new ProblemException("report.timeout");
        assertThat(worker.renderQueued(execution())).isEqualTo(1);

        assertThat(queries.labelJob(jobId, own(SOCIETY)))
                .get()
                .extracting(LabelJobView::status)
                .isEqualTo("FAILED");
        assertThat(audit("LABEL_JOB_FAILED")).singleElement();
        assertThat(events(LabelJobFailed.class)).containsExactly(new LabelJobFailed(jobId, SHOP));
    }

    @Test
    void requestGuardsRejectAnotherShopAnUnlistedSkuAndAnInvalidTemplateWithoutSideEffects() {
        refused(
                () -> request.handle(new PrintShelfLabels(OTHER_SHOP, List.of(rice)), own(SOCIETY)),
                "m3.label.location_not_owned");
        refused(
                () -> request.handle(new PrintShelfLabels(SHOP, List.of(unlisted)), own(SOCIETY)),
                "m3.label.sku_not_on_list");

        superuserJdbc()
                .update(
                        "insert into kernel.config_value (key, scope_entity_id, value, changed_by)"
                                + " values (?, ?, '\"missing-template\"'::jsonb, ?)",
                        TEMPLATE_KEY,
                        SOCIETY,
                        USER);
        config.invalidate(TEMPLATE_KEY);
        refused(
                () -> request.handle(new PrintShelfLabels(SHOP, List.of(rice)), own(SOCIETY)),
                "m3.label.template_invalid");

        assertThat(superuserJdbc().queryForObject("select count(*) from pricing.label_job", Integer.class))
                .isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void onlyAnOwnUserCanRequestANonEmptyUniqueShelfList() {
        refused(() -> request.handle(new PrintShelfLabels(SHOP, List.of()), own(SOCIETY)), "request.invalid");
        refused(() -> request.handle(new PrintShelfLabels(SHOP, List.of(rice, rice)), own(SOCIETY)), "request.invalid");
        ScopeContext view = new ScopeContext(
                USER,
                null,
                SOCIETY,
                List.of(new Scope(SOCIETY, null)),
                new Scope(SOCIETY, null),
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                Instant.now(),
                Locale.ENGLISH,
                null);
        refused(() -> request.handle(new PrintShelfLabels(SHOP, List.of(rice)), view), "scope.invalid");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    private static ScopeContext own(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                Instant.now(),
                Locale.ENGLISH,
                null);
    }

    private static JobExecution execution() {
        UUID runId = Ids.next();
        return new JobExecution() {
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
    }

    private static void insertEntity(JdbcTemplate admin, UUID entity, String code, String name, String language) {
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, district,"
                        + " financial_year_start_month, default_language, status)"
                        + " values (?, ?, 'MPCS', ?, 'Kurunegala', 1, ?, 'ACTIVE') on conflict (entity_id) do nothing",
                entity,
                code,
                name,
                language);
    }

    private static void insertLocation(
            JdbcTemplate admin, UUID location, UUID owner, String code, String name, String language) {
        admin.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language)"
                        + " values (?, ?, ?, 'SHOP', ?, ?) on conflict (location_id) do update set language = excluded.language",
                location,
                owner,
                code,
                name,
                language);
    }

    private static void insertSku(
            JdbcTemplate admin, UUID sku, String code, String nameSi, String nameTa, String nameEn) {
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, has_printed_mrp, tax_category_id)"
                        + " values (?, ?, ?, 'SHARED', ?, ?, ?, 'EA', false, true, ?)",
                sku,
                code,
                FEDERATION,
                nameEn,
                nameSi,
                nameTa,
                TAX_CATEGORY);
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }

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

    @TestConfiguration(proxyBeanMethods = false)
    static class RendererTestConfig {

        @Bean
        @Primary
        RecordingRenderer labelRecordingRenderer() {
            return new RecordingRenderer();
        }
    }

    static final class RecordingRenderer implements A4Renderer {

        RuntimeException failure;
        String templateId;
        Map<String, Object> data;
        Locale language;
        int calls;

        void reset() {
            failure = null;
            templateId = null;
            data = null;
            language = null;
            calls = 0;
        }

        @Override
        public Rendered render(String templateId, Map<String, Object> data, Locale language, ScopeContext ctx) {
            calls++;
            if (failure != null) throw failure;
            this.templateId = templateId;
            this.data = data;
            this.language = language;
            return new Rendered(
                    Ids.next(),
                    "reports/" + ctx.entityId() + "/labels.pdf",
                    URI.create("http://objects.test/labels.pdf"),
                    Instant.parse("2026-10-21T07:00:00Z"),
                    1,
                    "00");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, String>> labels() {
            return data == null ? new ArrayList<>() : (List<Map<String, String>>) data.get("labels");
        }

        @Override
        public URI presignGet(String objectKey, ScopeContext ctx) {
            return URI.create("http://objects.test/labels.pdf");
        }

        @Override
        public URI presignGetOfParty(String objectKey, UUID ownerEntityId, ScopeContext ctx) {
            return URI.create("http://objects.test/labels.pdf");
        }
    }
}
