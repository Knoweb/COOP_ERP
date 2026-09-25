package lk.coopfed.knoweb.m2catalogue.internal.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.ActivateLocalSku;
import lk.coopfed.knoweb.m2catalogue.api.ActivateSharedSku;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.DeactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.ReactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuActivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuCreated;
import lk.coopfed.knoweb.m2catalogue.api.SkuDeactivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.SkuReactivated;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SkuHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = UUID.fromString("0190e600-0000-7000-8000-000000000001");

    private static final UUID MPCS = UUID.fromString("0190e600-0000-7000-8000-000000000002");

    private static final UUID USER = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private static final UUID TAX_CATEGORY = UUID.fromString("0190e600-0000-7000-8000-000000000100");

    @Autowired
    CreateLocalSkuHandler createLocal;

    @Autowired
    CreateSharedSkuHandler createShared;

    @Autowired
    ActivateLocalSkuHandler activateLocal;

    @Autowired
    ActivateSharedSkuHandler activateShared;

    @Autowired
    DeactivateSkuHandler deactivate;

    @Autowired
    ReactivateSkuHandler reactivate;

    @BeforeEach
    void cleanCatalogue() {
        JdbcTemplate admin = superuserJdbc();

        admin.execute("truncate table catalogue.sku cascade");

        admin.update(
                """
                insert into catalogue.uom
                    (uom_code, name_en, is_weight)
                values
                    ('EA', 'Each', false)
                on conflict do nothing
                """);

        admin.update(
                """
                insert into catalogue.uom
                    (uom_code, name_en, is_weight)
                values
                    ('KG', 'Kilogram', true)
                on conflict do nothing
                """);

        admin.update(
                """
                insert into catalogue.tax_category
                    (tax_category_id, code, name_en, owner_entity_id)
                values
                    (?, 'M2TST', 'M2 test tax', ?)
                on conflict do nothing
                """,
                TAX_CATEGORY,
                FEDERATION);

        kernel.reset();
    }

    @AfterEach
    void removeTestTaxCategory() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX_CATEGORY);
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void createSkuPersistsDraftAuditsAndPublishes() {
        UUID skuId = createLocal.handle(
                new CreateSku(details("Fresh milk", "නැවුම් කිරි", "புதிய பால்", "EA", false, false, false)),
                own(MPCS));

        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        """
                        select sku_code, owner_entity_id, status
                        from catalogue.sku
                        where sku_id = ?
                        """,
                        skuId);

        assertThat(row.get("owner_entity_id")).isEqualTo(MPCS);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("sku_code").toString()).startsWith("SKU-").hasSize(12);

        String code = row.get("sku_code").toString();

        assertThat(audit("SKU_CREATED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("sku");
            assertThat(record.subject().id()).isEqualTo(skuId);
            assertThat(record.before()).isNull();
            assertThat(((Map<?, ?>) record.after()).get("status")).isEqualTo("DRAFT");
        });

        assertThat(events(SkuCreated.class)).containsExactly(new SkuCreated(skuId, MPCS, code, "DRAFT"));
    }

    @Test
    void localLifecycleIsDraftLocalInactiveLocal() {
        UUID skuId = createLocal.handle(
                new CreateSku(details("Local rice", null, null, "EA", false, false, false)), own(MPCS));

        kernel.reset();

        activateLocal.handle(new ActivateLocalSku(skuId), own(MPCS));

        assertThat(status(skuId)).isEqualTo("LOCAL");
        assertThat(audit("SKU_ACTIVATED")).hasSize(1);
        assertThat(events(SkuActivated.class)).singleElement().satisfies(event -> {
            assertThat(event.skuId()).isEqualTo(skuId);
            assertThat(event.status()).isEqualTo("LOCAL");
        });

        kernel.reset();

        deactivate.handle(new DeactivateSku(skuId, "SEASONAL", "Temporarily unavailable"), own(MPCS));

        assertThat(status(skuId)).isEqualTo("INACTIVE");
        assertThat(priorStatus(skuId)).isEqualTo("LOCAL");
        assertThat(audit("SKU_DEACTIVATED")).singleElement().satisfies(record -> assertThat(record.reason())
                .isEqualTo("SEASONAL: Temporarily unavailable"));
        assertThat(events(SkuDeactivated.class)).singleElement().satisfies(event -> {
            assertThat(event.skuId()).isEqualTo(skuId);
            assertThat(event.priorStatus()).isEqualTo("LOCAL");
        });

        kernel.reset();

        reactivate.handle(new ReactivateSku(skuId, "BACK_IN_STOCK", "Available again"), own(MPCS));

        assertThat(status(skuId)).isEqualTo("LOCAL");
        assertThat(priorStatus(skuId)).isNull();
        assertThat(audit("SKU_REACTIVATED")).singleElement().satisfies(record -> assertThat(record.reason())
                .isEqualTo("BACK_IN_STOCK: Available again"));
        assertThat(events(SkuReactivated.class)).singleElement().satisfies(event -> assertThat(event.status())
                .isEqualTo("LOCAL"));
    }

    @Test
    void sharedActivationRequiresSinhalaAndTamil() {
        UUID incomplete = createShared.handle(
                new CreateSku(details("Milk powder", null, null, "EA", false, false, false)), own(FEDERATION));

        kernel.reset();

        refused(
                () -> activateShared.handle(new ActivateSharedSku(incomplete), own(FEDERATION)),
                "m2.sku.shared_translations_required");

        assertThat(status(incomplete)).isEqualTo("DRAFT");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        UUID complete = createShared.handle(
                new CreateSku(details("Milk powder 400g", "කිරිපිටි 400g", "பால் மா 400g", "EA", false, false, false)),
                own(FEDERATION));

        kernel.reset();

        activateShared.handle(new ActivateSharedSku(complete), own(FEDERATION));

        assertThat(status(complete)).isEqualTo("SHARED");
        assertThat(audit("SKU_ACTIVATED")).hasSize(1);
        assertThat(events(SkuActivated.class)).singleElement().satisfies(event -> assertThat(event.status())
                .isEqualTo("SHARED"));
    }

    @Test
    void referenceAndTrackingGuardsFailBeforeMutation() {
        refused(
                () -> createLocal.handle(
                        new CreateSku(details("Unknown unit", null, null, "ZZZ", false, false, false)), own(MPCS)),
                "m2.sku.base_uom_unknown");

        SkuDetails unknownTax = new SkuDetails(
                "Unknown tax",
                null,
                null,
                null,
                null,
                null,
                "EA",
                false,
                false,
                false,
                false,
                null,
                UUID.randomUUID(),
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of());

        refused(() -> createLocal.handle(new CreateSku(unknownTax), own(MPCS)), "m2.sku.tax_category_unknown");

        assertThat(superuserJdbc().queryForObject("select count(*) from catalogue.sku", Integer.class))
                .isZero();

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void federationViewCannotWrite() {
        UUID skuId = createLocal.handle(
                new CreateSku(details("Own only", null, null, "EA", false, false, false)), own(MPCS));

        kernel.reset();

        refused(() -> activateLocal.handle(new ActivateLocalSku(skuId), federationView(MPCS)), "scope.invalid");

        assertThat(status(skuId)).isEqualTo("DRAFT");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void eventFailureRollsBackSkuAndAuditTogether() {
        kernel.failNextPublishWith(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> createLocal.handle(
                        new CreateSku(details("Rollback product", null, null, "EA", false, false, false)), own(MPCS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");

        assertThat(superuserJdbc().queryForObject("select count(*) from catalogue.sku", Integer.class))
                .isZero();

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        assertThat(kernel.rolledBackAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("SKU_CREATED");
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    private static ScopeContext federationView(UUID entity) {
        lk.coopfed.knoweb.kernel.api.Scope scope = new lk.coopfed.knoweb.kernel.api.Scope(entity, null);

        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(scope),
                scope,
                lk.coopfed.knoweb.kernel.api.PolicyClass.FEDERATION_VIEW,
                java.util.Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
    }

    private static SkuDetails details(
            String en, String si, String ta, String uom, boolean weight, boolean batch, boolean expiry) {

        return new SkuDetails(
                en,
                si,
                ta,
                null,
                null,
                null,
                uom,
                weight,
                batch,
                expiry,
                false,
                null,
                TAX_CATEGORY,
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of("test", true));
    }

    private static String status(UUID skuId) {
        return superuserJdbc().queryForObject("select status from catalogue.sku where sku_id = ?", String.class, skuId);
    }

    private static String priorStatus(UUID skuId) {
        return superuserJdbc()
                .queryForObject("select prior_status from catalogue.sku where sku_id = ?", String.class, skuId);
    }

    private static void refused(ThrowingCallable call, String messageId) {

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
}
