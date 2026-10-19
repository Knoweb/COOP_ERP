package lk.coopfed.knoweb.m2catalogue.internal.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.api.MergeIntoShared;
import lk.coopfed.knoweb.m2catalogue.api.PromoteToShared;
import lk.coopfed.knoweb.m2catalogue.api.ReactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuActivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuCreated;
import lk.coopfed.knoweb.m2catalogue.api.SkuDeactivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.SkuMerged;
import lk.coopfed.knoweb.m2catalogue.api.SkuPromoted;
import lk.coopfed.knoweb.m2catalogue.api.SkuReactivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuShared;
import lk.coopfed.knoweb.m2catalogue.api.SkuUpdated;
import lk.coopfed.knoweb.m2catalogue.api.UpdateSku;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class SkuHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = UUID.fromString("0190e600-0000-7000-8000-000000000001");

    private static final UUID MPCS = UUID.fromString("0190e600-0000-7000-8000-000000000002");

    private static final UUID BARCODE_TEST_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000099");

    private static final UUID USER = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private static final UUID TAX_CATEGORY = UUID.fromString("0190e600-0000-7000-8000-000000000100");

    @Autowired
    CreateLocalSkuHandler createLocal;

    @Autowired
    SkuCommandRouter router;

    @Autowired
    ActivateLocalSkuHandler activateLocal;

    @Autowired
    ActivateSharedSkuHandler activateShared;

    @Autowired
    DeactivateSkuHandler deactivate;

    @Autowired
    ReactivateSkuHandler reactivate;

    @Autowired
    UpdateLocalSkuHandler updateLocal;

    @Autowired
    UpdateSharedSkuHandler updateShared;

    @Autowired
    PromoteToSharedHandler promoteHandler;

    @Autowired
    MergeIntoSharedHandler mergeHandler;

    @Autowired
    lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry config;

    // Forces a code another entity holds, to prove the retry on the unique index.
    @MockitoSpyBean
    SkuCodeGenerator codes;

    // M5 owns the lots; a stub answers for it (false unless a test says otherwise).
    @MockitoBean
    InventoryLotQuery inventory;

    // The shared path is the Federation's (FederationCaller), which the platform names here.
    @DynamicPropertySource
    static void federation(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.system.entity-id", FEDERATION::toString);
    }

    @BeforeEach
    void cleanCatalogue() {
        if (config != null) {
            config.invalidate("catalogue.local_sku_requires_barcode");
        }
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
        admin.update("delete from catalogue.tag where tag_code = 'local-promo'");
        admin.update("delete from kernel.config_value where key = 'catalogue.local_sku_requires_barcode'");
        if (config != null) {
            config.invalidate("catalogue.local_sku_requires_barcode");
        }
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
        UUID incomplete = createLocal.handle(
                new CreateSku(details("Milk powder", null, null, "EA", false, false, false)), own(FEDERATION));

        kernel.reset();

        refused(
                () -> activateShared.handle(new ActivateSharedSku(incomplete), own(FEDERATION)),
                "m2.sku.shared_translations_required");

        assertThat(status(incomplete)).isEqualTo("DRAFT");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        UUID complete = createLocal.handle(
                new CreateSku(details("Milk powder 400g", "කිරිපිටි 400g", "பால் மா 400g", "EA", false, false, false)),
                own(FEDERATION));

        kernel.reset();

        activateShared.handle(new ActivateSharedSku(complete), own(FEDERATION));

        assertThat(status(complete)).isEqualTo("SHARED");
        assertThat(audit("SKU_SHARED")).singleElement().satisfies(record -> {
            assertThat(((Map<?, ?>) record.before()).get("status")).isEqualTo("DRAFT");
            assertThat(((Map<?, ?>) record.after()).get("status")).isEqualTo("SHARED");
        });
        assertThat(audit("SKU_ACTIVATED")).isEmpty();
        assertThat(events(SkuShared.class))
                .containsExactly(new SkuShared(complete, FEDERATION, code(complete), "SHARED"));
        assertThat(events(SkuActivated.class)).isEmpty();
    }

    @Test
    void anEntityThatIsNotTheFederationIsRefusedTheSharedPath() {
        UUID draft = createLocal.handle(
                new CreateSku(details("Society milk", "කිරි", "பால்", "EA", false, false, false)), own(MPCS));

        kernel.reset();

        refused(() -> activateShared.handle(new ActivateSharedSku(draft), own(MPCS)), "m2.sku.federation_only");
        refused(
                () -> updateShared.handle(
                        new UpdateSku(draft, details("Changed", "කිරි", "பால்", "EA", false, false, false)), own(MPCS)),
                "m2.sku.federation_only");

        assertThat(status(draft)).isEqualTo("DRAFT");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theRouterSendsAnUpdateWhereTheSkuStatusSays() {
        UUID shared = createLocal.handle(
                new CreateSku(details("Shared salt", "ලුණු", "உப்பு", "EA", false, false, false)), own(FEDERATION));
        activateShared.handle(new ActivateSharedSku(shared), own(FEDERATION));
        UUID local = createLocal.handle(
                new CreateSku(details("Local salt", null, null, "EA", false, false, false)), own(MPCS));
        activateLocal.handle(new ActivateLocalSku(local), own(MPCS));

        kernel.reset();

        // A society sees the SHARED SKU (shared_read), so the router sends it to the shared path,
        // whose guard refuses it; the local handler refuses a SHARED SKU as well.
        refused(
                () -> router.update(
                        new UpdateSku(shared, details("Renamed", "ලුණු", "உப்பு", "EA", false, false, false)),
                        own(MPCS)),
                "m2.sku.federation_only");
        refused(
                () -> updateLocal.handle(
                        new UpdateSku(shared, details("Renamed", "ලුණු", "உப்பு", "EA", false, false, false)),
                        own(FEDERATION)),
                "m2.sku.federation_only");
        assertThat(kernel.committedAudit()).isEmpty();

        router.update(
                new UpdateSku(shared, details("Shared salt 1kg", "ලුණු", "உப்பு", "EA", false, false, false)),
                own(FEDERATION));
        router.update(
                new UpdateSku(local, details("Local salt 1kg", null, null, "EA", false, false, false)), own(MPCS));

        assertThat(audit("SKU_UPDATED")).hasSize(2);
        assertThat(events(SkuUpdated.class))
                .extracting(SkuUpdated::skuId, SkuUpdated::status)
                .containsExactly(tuple(shared, "SHARED"), tuple(local, "LOCAL"));
    }

    @Test
    void theFederationCannotChangeAnotherEntitysLocalSku() {
        UUID local = createLocal.handle(
                new CreateSku(details("Society rice", null, null, "EA", false, false, false)), own(MPCS));
        activateLocal.handle(new ActivateLocalSku(local), own(MPCS));

        kernel.reset();

        // Row-level security hides another entity's LOCAL SKU from the Federation's OWN scope.
        refused(
                () -> router.update(
                        new UpdateSku(local, details("Taken over", null, null, "EA", false, false, false)),
                        own(FEDERATION)),
                "m2.sku.not_found");
        refused(
                () -> deactivate.handle(new DeactivateSku(local, "SEASONAL", null), own(FEDERATION)),
                "m2.sku.not_found");

        assertThat(status(local)).isEqualTo("LOCAL");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aCodeAnotherEntityHoldsIsReplacedByAFreshOne() {
        UUID first = createLocal.handle(
                new CreateSku(details("First holder", null, null, "EA", false, false, false)), own(MPCS));
        String taken = code(first);

        kernel.reset();

        // The Federation cannot see the society's draft, so the handler's own check passes and
        // the insert meets the unique index; the router runs the create again with a new code.
        clearInvocations(codes);
        doReturn(taken).doCallRealMethod().when(codes).next();

        UUID second = router.create(
                new CreateSku(details("Second holder", null, null, "EA", false, false, false)), own(FEDERATION));

        assertThat(code(second)).isNotEqualTo(taken).startsWith("SKU-");
        assertThat(audit("SKU_CREATED"))
                .singleElement()
                .satisfies(record -> assertThat(record.subject().id()).isEqualTo(second));
        assertThat(events(SkuCreated.class)).extracting(SkuCreated::skuId).containsExactly(second);
        // The clash is met at the insert, before anything is audited: one code taken, one fresh.
        verify(codes, times(2)).next();
    }

    @Test
    void aCodeThatKeepsClashingEndsInAProblemNotAServerError() {
        UUID first = createLocal.handle(
                new CreateSku(details("First holder", null, null, "EA", false, false, false)), own(MPCS));
        String taken = code(first);

        kernel.reset();
        doReturn(taken).when(codes).next();

        refused(
                () -> router.create(
                        new CreateSku(details("Unlucky", null, null, "EA", false, false, false)), own(FEDERATION)),
                "m2.sku.code_generation_failed");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(superuserJdbc().queryForObject("select count(*) from catalogue.sku", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theUnitAndTrackingCannotChangeOnceLotsExist() {
        UUID local = createLocal.handle(
                new CreateSku(details("Lot rice", null, null, "EA", false, false, false)), own(MPCS));
        activateLocal.handle(new ActivateLocalSku(local), own(MPCS));
        when(inventory.hasAnyLot(local)).thenReturn(true);

        kernel.reset();

        refused(
                () -> router.update(
                        new UpdateSku(local, details("Lot rice", null, null, "EA", false, true, false)), own(MPCS)),
                "m2.sku.has_lots");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // A change that leaves the unit and the tracking alone is still allowed.
        router.update(new UpdateSku(local, details("Lot rice 5kg", null, null, "EA", false, false, false)), own(MPCS));
        assertThat(audit("SKU_UPDATED")).hasSize(1);
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

    @Test
    void activateSharedRefusesNonGovernedTags() {
        UUID skuId = createLocal.handle(
                new CreateSku(details("Shared Biscuits", "බිස්කට්", "பிஸ்கட்", "EA", false, false, false)),
                own(FEDERATION));

        UUID tagId = UUID.randomUUID();
        superuserJdbc()
                .update(
                        """
                insert into catalogue.tag (tag_id, tag_code, name_en, governed, owner_entity_id)
                values (?, 'local-promo', 'Local Promotion', false, ?)
                """,
                        tagId,
                        FEDERATION);
        superuserJdbc()
                .update(
                        """
                insert into catalogue.sku_tag (sku_id, tag_id, owner_entity_id)
                values (?, ?, ?)
                """,
                        skuId,
                        tagId,
                        FEDERATION);

        kernel.reset();

        refused(
                () -> activateShared.handle(new ActivateSharedSku(skuId), own(FEDERATION)),
                "m2.sku.governed_tags_only");

        assertThat(status(skuId)).isEqualTo("DRAFT");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void activateSharedRefusesCaseBarcodeWithoutConversion() {
        UUID skuId = createLocal.handle(
                new CreateSku(details("Shared Juice", "යුෂ", "சாறு", "EA", false, false, false)), own(FEDERATION));

        superuserJdbc()
                .update(
                        """
                insert into catalogue.uom (uom_code, name_en, is_weight)
                values ('CASE', 'Case', false)
                on conflict do nothing
                """);

        superuserJdbc()
                .update(
                        """
                insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id, status)
                values ('4791234560011', 'EAN13', ?, 'CASE', ?, 'ACTIVE')
                """,
                        skuId,
                        FEDERATION);

        kernel.reset();

        refused(
                () -> activateShared.handle(new ActivateSharedSku(skuId), own(FEDERATION)),
                "m2.sku.case_conversion_required");

        assertThat(status(skuId)).isEqualTo("DRAFT");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        superuserJdbc()
                .update(
                        """
                insert into catalogue.sku_uom_conversion (sku_id, uom_code, factor_to_base, effective_from, owner_entity_id)
                values (?, 'CASE', 12.0, current_date, ?)
                """,
                        skuId,
                        FEDERATION);

        activateShared.handle(new ActivateSharedSku(skuId), own(FEDERATION));
        assertThat(status(skuId)).isEqualTo("SHARED");
    }

    @Test
    void activateLocalRefusesWhenBarcodeRequiredAndMissing() {
        UUID entity = BARCODE_TEST_ENTITY;
        UUID skuId = createLocal.handle(
                new CreateSku(details("Local Veg", null, null, "EA", false, false, false)), own(entity));

        superuserJdbc()
                .update(
                        """
                        insert into kernel.config_item (key, value_type, description_en, default_value, scope_kind, change_permission, module)
                        values ('catalogue.local_sku_requires_barcode', 'BOOLEAN', 'Barcode required for local SKU', 'false'::jsonb, 'ENTITY', 'sys.config.manage', 'm2catalogue')
                        on conflict (key) do nothing
                        """);

        superuserJdbc()
                .update(
                        """
                        insert into kernel.config_value (key, scope_entity_id, value, reason)
                        values ('catalogue.local_sku_requires_barcode', ?, 'true'::jsonb, 'test')
                        """,
                        entity);
        config.invalidate("catalogue.local_sku_requires_barcode");

        kernel.reset();

        try {
            refused(() -> activateLocal.handle(new ActivateLocalSku(skuId), own(entity)), "m2.sku.barcode_required");

            assertThat(status(skuId)).isEqualTo("DRAFT");
            assertThat(kernel.committedAudit()).isEmpty();
            assertThat(kernel.committedEvents()).isEmpty();

            superuserJdbc()
                    .update(
                            """
                    insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id, status)
                    values ('4791234560028', 'EAN13', ?, 'EA', ?, 'ACTIVE')
                    """,
                            skuId,
                            entity);

            activateLocal.handle(new ActivateLocalSku(skuId), own(entity));
            assertThat(status(skuId)).isEqualTo("LOCAL");
        } finally {
            superuserJdbc()
                    .update("delete from kernel.config_value where key = 'catalogue.local_sku_requires_barcode'");
            config.invalidate("catalogue.local_sku_requires_barcode");
        }
    }

    @Test
    void promoteToSharedUpdatesStatusAndOwnerInsertsAliasAuditsAndPublishes() {
        UUID local = createLocal.handle(
                new CreateSku(details("Local Jam", "ජෑම්", "ஜாம்", "EA", false, false, false)), own(MPCS));
        activateLocal.handle(new ActivateLocalSku(local), own(MPCS));
        assertThat(status(local)).isEqualTo("LOCAL");

        kernel.reset();

        UUID promoted = promoteHandler.handle(new PromoteToShared(local), own(FEDERATION));
        assertThat(promoted).isEqualTo(local);

        assertThat(status(local)).isEqualTo("SHARED");
        UUID currentOwner = superuserJdbc()
                .queryForObject("select owner_entity_id from catalogue.sku where sku_id = ?", UUID.class, local);
        assertThat(currentOwner).isEqualTo(FEDERATION);

        // Verify sku_alias self-reference for origin entity
        UUID canonical = superuserJdbc()
                .queryForObject(
                        "select canonical_sku_id from catalogue.sku_alias where alias_sku_id = ?", UUID.class, local);
        assertThat(canonical).isEqualTo(local);
        UUID aliasOwner = superuserJdbc()
                .queryForObject(
                        "select owner_entity_id from catalogue.sku_alias where alias_sku_id = ?", UUID.class, local);
        assertThat(aliasOwner).isEqualTo(MPCS);

        assertThat(audit("SKU_PROMOTED")).hasSize(1);
        List<SkuPromoted> events = events(SkuPromoted.class);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).skuId()).isEqualTo(local);
        assertThat(events.get(0).originEntityId()).isEqualTo(MPCS);
        assertThat(events.get(0).status()).isEqualTo("SHARED");
    }

    @Test
    void mergeIntoSharedRetiresSourceMovesBarcodesInsertsAliasAuditsAndPublishes() {
        UUID local = createLocal.handle(
                new CreateSku(details("Local Biscuit", "බිස්කට්", "பிஸ்கட்", "EA", false, false, false)), own(MPCS));
        activateLocal.handle(new ActivateLocalSku(local), own(MPCS));

        UUID shared = router.create(
                new CreateSku(details("Shared Biscuit", "බිස්කට්", "பிஸ்கட்", "EA", false, false, false)),
                own(FEDERATION));
        activateShared.handle(new ActivateSharedSku(shared), own(FEDERATION));

        // Add a barcode to local SKU
        superuserJdbc()
                .update(
                        """
                insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id)
                values ('4791000999001', 'EAN13', ?, 'EA', ?)
                """,
                        local,
                        MPCS);

        kernel.reset();

        UUID result = mergeHandler.handle(new MergeIntoShared(local, shared), own(FEDERATION));
        assertThat(result).isEqualTo(shared);

        assertThat(status(local)).isEqualTo("INACTIVE");
        assertThat(priorStatus(local)).isEqualTo("LOCAL");

        // Verify barcode moved to shared SKU
        UUID barcodeSku = superuserJdbc()
                .queryForObject("select sku_id from catalogue.sku_barcode where barcode = '4791000999001'", UUID.class);
        assertThat(barcodeSku).isEqualTo(shared);

        // Verify sku_alias points local -> shared
        UUID canonical = superuserJdbc()
                .queryForObject(
                        "select canonical_sku_id from catalogue.sku_alias where alias_sku_id = ?", UUID.class, local);
        assertThat(canonical).isEqualTo(shared);

        assertThat(audit("SKU_MERGED")).hasSize(1);
        List<SkuMerged> events = events(SkuMerged.class);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).sourceSkuId()).isEqualTo(local);
        assertThat(events.get(0).canonicalSkuId()).isEqualTo(shared);
        assertThat(events.get(0).originEntityId()).isEqualTo(MPCS);
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

    private static String code(UUID skuId) {
        return superuserJdbc()
                .queryForObject("select sku_code from catalogue.sku where sku_id = ?", String.class, skuId);
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
