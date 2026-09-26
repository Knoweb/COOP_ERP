package lk.coopfed.knoweb.m2catalogue.internal.queries;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.ActivateLocalSku;
import lk.coopfed.knoweb.m2catalogue.api.ActivateSharedSku;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.internal.sku.ActivateLocalSkuHandler;
import lk.coopfed.knoweb.m2catalogue.internal.sku.ActivateSharedSkuHandler;
import lk.coopfed.knoweb.m2catalogue.internal.sku.CreateLocalSkuHandler;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class CatalogueQueriesPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = UUID.fromString("0190e610-0000-7000-8000-000000000001");

    private static final UUID MPCS = UUID.fromString("0190e610-0000-7000-8000-000000000002");

    private static final UUID OTHER = UUID.fromString("0190e610-0000-7000-8000-000000000003");

    private static final UUID THIRD = UUID.fromString("0190e610-0000-7000-8000-000000000004");

    private static final UUID USER = UUID.fromString("0190e610-0000-7000-8000-000000000010");

    private static final UUID TAX_CATEGORY = UUID.fromString("0190e610-0000-7000-8000-000000000100");

    @Autowired
    CreateLocalSkuHandler createLocal;

    @Autowired
    ActivateLocalSkuHandler activateLocal;

    @Autowired
    ActivateSharedSkuHandler activateShared;

    @Autowired
    CatalogueQueries queries;

    // The shared path is the Federation's (FederationCaller), which the platform names here.
    @DynamicPropertySource
    static void federation(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.system.entity-id", FEDERATION::toString);
    }

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
                insert into catalogue.tax_category
                    (tax_category_id, code, name_en, owner_entity_id)
                values
                    (?, 'M2QRY', 'M2 query tax', ?)
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
    void draftAndLocalAreVisibleOnlyToTheirOwnerWhileFederationViewReadsAll() {
        UUID mineDraft = createLocal.handle(
                new CreateSku(details("My draft", null, null, Map.of("private", "draft"))), own(MPCS));

        UUID mineLocal = createLocal.handle(
                new CreateSku(details("My local", null, null, Map.of("private", "local"))), own(MPCS));

        activateLocal.handle(new ActivateLocalSku(mineLocal), own(MPCS));

        UUID theirDraft = createLocal.handle(
                new CreateSku(details("Their draft", null, null, Map.of("private", "other"))), own(OTHER));

        SkuPage mine = queries.listSkus(new SkuFilter(null, null, "en", 0, 50), own(MPCS));

        assertThat(mine.items())
                .extracting(SkuView::skuId)
                .containsExactlyInAnyOrder(mineDraft, mineLocal)
                .doesNotContain(theirDraft);

        SkuPage theirs = queries.listSkus(new SkuFilter(null, null, "en", 0, 50), own(OTHER));

        assertThat(theirs.items()).extracting(SkuView::skuId).containsExactly(theirDraft);

        SkuPage federation = queries.listSkus(new SkuFilter(null, null, "en", 0, 50), view(FEDERATION));

        assertThat(federation.items())
                .extracting(SkuView::skuId)
                .containsExactlyInAnyOrder(mineDraft, mineLocal, theirDraft);
    }

    @Test
    void aSharedSkuIsReadableByAnotherEntity() {
        UUID shared = createLocal.handle(
                new CreateSku(details(
                        "Ceylon milk powder 400g",
                        "ලංකා කිරිපිටි 400g",
                        "இலங்கை பால் மா 400g",
                        Map.of("steward", "federation"))),
                own(FEDERATION));

        activateShared.handle(new ActivateSharedSku(shared), own(FEDERATION));

        assertThat(queries.getSku(shared, own(OTHER)))
                .isPresent()
                .get()
                .extracting(SkuView::status)
                .isEqualTo("SHARED");

        assertThat(queries.listSkus(new SkuFilter("SHARED", null, "en", 0, 50), own(THIRD))
                        .items())
                .extracting(SkuView::skuId)
                .containsExactly(shared);
    }

    @Test
    void searchFindsCodeAndAllThreeNames() {
        UUID shared = createLocal.handle(
                new CreateSku(details("Milk powder 400g", "කිරිපිටි 400g", "பால் மா 400g", Map.of())), own(FEDERATION));

        activateShared.handle(new ActivateSharedSku(shared), own(FEDERATION));

        SkuView view = queries.getSku(shared, own(MPCS)).orElseThrow();

        assertSearch("Milk", "en", MPCS, shared);
        assertSearch("කිරි", "si", MPCS, shared);
        assertSearch("பால்", "ta", MPCS, shared);
        assertSearch(view.skuCode(), "en", MPCS, shared);
    }

    @Test
    void percentAndUnderscoreInTheQueryAreLettersNotWildcards() {
        UUID percent = createLocal.handle(new CreateSku(details("Soap 50% extra", null, null, Map.of())), own(MPCS));
        UUID plain = createLocal.handle(new CreateSku(details("Soap 500 extra", null, null, Map.of())), own(MPCS));
        UUID underscore = createLocal.handle(new CreateSku(details("Tea_bags", null, null, Map.of())), own(MPCS));
        UUID space = createLocal.handle(new CreateSku(details("Tea bags", null, null, Map.of())), own(MPCS));
        UUID backslash = createLocal.handle(new CreateSku(details("Rice \\ red", null, null, Map.of())), own(MPCS));

        assertThat(search("50%", MPCS)).containsExactly(percent).doesNotContain(plain);
        assertThat(search("a_b", MPCS)).containsExactly(underscore).doesNotContain(space);
        assertThat(search("%", MPCS)).containsExactly(percent);
        assertThat(search("\\", MPCS)).containsExactly(backslash);
    }

    @Test
    void theCodeMatchesByPrefixOnly() {
        UUID sku = createLocal.handle(new CreateSku(details("Coconut oil", null, null, Map.of())), own(MPCS));
        String code = queries.getSku(sku, own(MPCS)).orElseThrow().skuCode();

        assertThat(search(code.substring(0, 7).toLowerCase(Locale.ROOT), MPCS)).containsExactly(sku);
        assertThat(search(code.substring(5), MPCS)).isEmpty();
    }

    @Test
    void theClosestNameComesFirstAmongEqualNames() {
        // The same Sinhala name, so the Sinhala display order ties; the English names differ, and
        // the one the query matches exactly must come first whatever the codes are.
        for (int round = 0; round < 3; round++) {
            superuserJdbc().execute("truncate table catalogue.sku cascade");

            UUID loose = createLocal.handle(
                    new CreateSku(details("Sugar packet one kilo", "සීනි", null, Map.of())), own(MPCS));
            UUID exact = createLocal.handle(new CreateSku(details("Sugar", "සීනි", null, Map.of())), own(MPCS));

            assertThat(queries.searchSku(new SkuFilter(null, "Sugar", "si", 0, 50), own(MPCS))
                            .items())
                    .extracting(SkuView::skuId)
                    .containsExactly(exact, loose);
        }
    }

    @Test
    void theOffsetIsCappedAndThePageAtTheCapHasNoNext() {
        createLocal.handle(new CreateSku(details("Only one", null, null, Map.of())), own(MPCS));

        SkuFilter deep = new SkuFilter(null, null, "en", Integer.MAX_VALUE, 50);

        assertThat(deep.normalizedOffset()).isEqualTo(SkuFilter.MAX_OFFSET);
        assertThat(queries.listSkus(deep, own(MPCS)).items()).isEmpty();
        assertThat(queries.listSkus(deep, own(MPCS)).nextOffset()).isNull();
    }

    private List<UUID> search(String text, UUID entity) {
        return queries.searchSku(new SkuFilter(null, text, "en", 0, 50), own(entity)).items().stream()
                .map(SkuView::skuId)
                .toList();
    }

    @Test
    void anotherOwnersLocalSkuDoesNotLeakThroughSearch() {
        UUID hidden = createLocal.handle(
                new CreateSku(details("Secret local tea", null, null, Map.of("secret", true))), own(OTHER));

        activateLocal.handle(new ActivateLocalSku(hidden), own(OTHER));

        assertThat(queries.searchSku(new SkuFilter(null, "Secret", "en", 0, 50), own(MPCS))
                        .items())
                .isEmpty();

        assertThat(queries.searchSku(new SkuFilter(null, "Secret", "en", 0, 50), view(FEDERATION))
                        .items())
                .extracting(SkuView::skuId)
                .containsExactly(hidden);
    }

    @Test
    void pagingAndDisplayNameOrderingAreDeterministic() {
        UUID gamma = createLocal.handle(new CreateSku(details("Gamma", null, null, Map.of())), own(MPCS));

        UUID alpha = createLocal.handle(new CreateSku(details("Alpha", null, null, Map.of())), own(MPCS));

        UUID beta = createLocal.handle(new CreateSku(details("Beta", null, null, Map.of())), own(MPCS));

        SkuPage first = queries.listSkus(new SkuFilter(null, null, "en", 0, 2), own(MPCS));

        assertThat(first.items()).extracting(SkuView::nameEn).containsExactly("Alpha", "Beta");

        assertThat(first.nextOffset()).isEqualTo(2);

        SkuPage second = queries.listSkus(new SkuFilter(null, null, "en", first.nextOffset(), 2), own(MPCS));

        assertThat(second.items()).extracting(SkuView::nameEn).containsExactly("Gamma");

        assertThat(second.nextOffset()).isNull();

        assertThat(first.items()).extracting(SkuView::skuId).containsExactly(alpha, beta);

        assertThat(second.items()).extracting(SkuView::skuId).containsExactly(gamma);
    }

    @Test
    void attributesAreReturnedOnlyToTheOwningOwnScope() {
        UUID local = createLocal.handle(
                new CreateSku(details("Private attributes", null, null, Map.of("internalClass", "A"))), own(MPCS));

        activateLocal.handle(new ActivateLocalSku(local), own(MPCS));

        SkuView owner = queries.getSku(local, own(MPCS)).orElseThrow();

        assertThat(owner.attributes()).containsEntry("internalClass", "A");

        SkuView federation = queries.getSku(local, view(FEDERATION)).orElseThrow();

        assertThat(federation.attributes()).isNull();
    }

    private void assertSearch(String text, String language, UUID entity, UUID expected) {

        assertThat(queries.searchSku(new SkuFilter(null, text, language, 0, 50), own(entity))
                        .items())
                .extracting(SkuView::skuId)
                .contains(expected);
    }

    private static SkuDetails details(String en, String si, String ta, Map<String, Object> attributes) {

        return new SkuDetails(
                en,
                si,
                ta,
                null,
                null,
                null,
                "EA",
                false,
                false,
                false,
                false,
                null,
                TAX_CATEGORY,
                "AUTO_LOWEST",
                "PURCHASED",
                attributes);
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    private static ScopeContext view(UUID entity) {
        Scope scope = new Scope(entity, null);

        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(scope),
                scope,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }
}
