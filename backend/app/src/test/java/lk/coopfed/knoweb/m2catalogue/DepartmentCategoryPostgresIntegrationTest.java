package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.DefineDepartmentCategory;
import lk.coopfed.knoweb.m2catalogue.api.DepartmentCategoryDefined;
import lk.coopfed.knoweb.m2catalogue.api.DepartmentCategoryUpdated;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.UpdateDepartmentCategory;
import lk.coopfed.knoweb.m2catalogue.internal.department.DefineDepartmentCategoryHandler;
import lk.coopfed.knoweb.m2catalogue.internal.department.UpdateDepartmentCategoryHandler;
import lk.coopfed.knoweb.m2catalogue.internal.sku.CreateLocalSkuHandler;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.DepartmentCategoryView;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DepartmentCategoryPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID MPCS = UUID.fromString("0190e640-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e640-0000-7000-8000-000000000010");

    private static final String TEST_DEPT_CODE = "TEST_BEV";

    @Autowired
    private DefineDepartmentCategoryHandler defineHandler;

    @Autowired
    private UpdateDepartmentCategoryHandler updateHandler;

    @Autowired
    private CreateLocalSkuHandler createSkuHandler;

    @Autowired
    private CatalogueQueries queries;

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id) values ('00000000-0000-0000-0000-000000000100', 'STD_TEST', 'Standard', ?) on conflict do nothing",
                FEDERATION);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.department_category where code = ?", TEST_DEPT_CODE);
    }

    @Test
    void federationDefinesAndUpdatesDepartmentCategoryAndAudits() {
        // 1. Define Department Category
        UUID deptId = defineHandler.handle(
                new DefineDepartmentCategory(
                        TEST_DEPT_CODE, "Test Beverages", "පරීක්ෂණ බීම", "சோதனை பானங்கள்", "Beverages dept", 50),
                own(FEDERATION));

        assertThat(deptId).isNotNull();

        // Verify database row
        List<Map<String, Object>> rows = superuserJdbc()
                .queryForList(
                        "select code, name_en, name_si, name_ta, description, display_order, status, owner_entity_id from catalogue.department_category where department_id = ?",
                        deptId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("code")).isEqualTo(TEST_DEPT_CODE);
            assertThat(row.get("name_en")).isEqualTo("Test Beverages");
            assertThat(row.get("name_si")).isEqualTo("පරීක්ෂණ බීම");
            assertThat(row.get("name_ta")).isEqualTo("சோதனை பானங்கள்");
            assertThat(row.get("description")).isEqualTo("Beverages dept");
            assertThat(row.get("display_order")).isEqualTo(50);
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            assertThat(row.get("owner_entity_id")).isNull();
        });

        // Verify audit and event
        assertThat(audit("DEPARTMENT_CATEGORY_DEFINED")).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(deptId.toString());
        });
        assertThat(events(DepartmentCategoryDefined.class)).singleElement().satisfies(event -> {
            assertThat(event.departmentId()).isEqualTo(deptId);
            assertThat(event.code()).isEqualTo(TEST_DEPT_CODE);
            assertThat(event.status()).isEqualTo("ACTIVE");
        });

        // 2. Both Federation and MPCS can read via CatalogueQueries (authenticated_read RLS)
        List<DepartmentCategoryView> fedList = queries.departmentCategories(own(FEDERATION));
        assertThat(fedList).anyMatch(d -> d.departmentId().equals(deptId));

        List<DepartmentCategoryView> mpcsList = queries.departmentCategories(own(MPCS));
        assertThat(mpcsList).anyMatch(d -> d.departmentId().equals(deptId));

        DepartmentCategoryView singleView =
                queries.getDepartmentCategory(deptId, own(MPCS)).orElseThrow();
        assertThat(singleView.code()).isEqualTo(TEST_DEPT_CODE);
        assertThat(singleView.nameEn()).isEqualTo("Test Beverages");

        // 3. Update Department Category
        updateHandler.handle(
                new UpdateDepartmentCategory(
                        deptId,
                        "Updated Beverages",
                        "යාවත්කාලීන බීම",
                        "புதுப்பிக்கப்பட்ட பானங்கள்",
                        "New desc",
                        55,
                        "INACTIVE"),
                own(FEDERATION));

        DepartmentCategoryView updatedView =
                queries.getDepartmentCategory(deptId, own(FEDERATION)).orElseThrow();
        assertThat(updatedView.nameEn()).isEqualTo("Updated Beverages");
        assertThat(updatedView.displayOrder()).isEqualTo(55);
        assertThat(updatedView.status()).isEqualTo("INACTIVE");

        assertThat(audit("DEPARTMENT_CATEGORY_UPDATED")).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(deptId.toString());
        });
        assertThat(events(DepartmentCategoryUpdated.class)).singleElement().satisfies(event -> {
            assertThat(event.departmentId()).isEqualTo(deptId);
            assertThat(event.status()).isEqualTo("INACTIVE");
        });
    }

    @Test
    void nonFederationCannotDefineDepartmentCategory() {
        assertThatThrownBy(() -> defineHandler.handle(
                        new DefineDepartmentCategory(TEST_DEPT_CODE, "Beverages", null, null, null, 10), own(MPCS)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.federation_only"));
    }

    @Test
    void nonFederationCannotUpdateDepartmentCategory() {
        UUID deptId = defineHandler.handle(
                new DefineDepartmentCategory(
                        TEST_DEPT_CODE, "Test Beverages", "පරීක්ෂණ බීම", "சோதனை பானங்கள்", null, 10),
                own(FEDERATION));

        assertThatThrownBy(() -> updateHandler.handle(
                        new UpdateDepartmentCategory(deptId, "New Name", null, null, null, 10, "ACTIVE"), own(MPCS)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.federation_only"));
    }

    @Test
    void duplicateDepartmentCodeThrows() {
        defineHandler.handle(
                new DefineDepartmentCategory(
                        TEST_DEPT_CODE, "Test Beverages", "පරීක්ෂණ බීම", "சோதனை பானங்கள்", null, 10),
                own(FEDERATION));

        assertThatThrownBy(() -> defineHandler.handle(
                        new DefineDepartmentCategory(TEST_DEPT_CODE, "Another Beverages", null, null, null, 20),
                        own(FEDERATION)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.department.code_exists"));
    }

    @Test
    void skuCreationWithDepartmentPersistsAndReadsDepartmentId() {
        // Define active department
        UUID deptId = defineHandler.handle(
                new DefineDepartmentCategory(
                        TEST_DEPT_CODE, "Test Beverages", "පරීක්ෂණ බීම", "சோதனை பானங்கள்", null, 10),
                own(FEDERATION));

        SkuDetails details = new SkuDetails(
                "Fresh Orange Juice",
                "දොඩම් යුෂ",
                "ஆரஞ்சு சாறு",
                null,
                null,
                null,
                "EA",
                false,
                false,
                false,
                true,
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000100"),
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of(),
                deptId);

        UUID skuId = createSkuHandler.handle(new CreateSku(details), own(MPCS));
        assertThat(skuId).isNotNull();

        SkuView skuView = queries.getSku(skuId, own(MPCS)).orElseThrow();
        assertThat(skuView.departmentId()).isEqualTo(deptId);
    }

    @Test
    void skuCreationWithUnknownOrInactiveDepartmentThrows() {
        UUID unknownDeptId = UUID.randomUUID();
        SkuDetails detailsUnknown = new SkuDetails(
                "Unknown Dept Item",
                null,
                null,
                null,
                null,
                null,
                "EA",
                false,
                false,
                false,
                true,
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000100"),
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of(),
                unknownDeptId);

        assertThatThrownBy(() -> createSkuHandler.handle(new CreateSku(detailsUnknown), own(MPCS)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.department_unknown"));

        // Now test inactive department
        UUID deptId = defineHandler.handle(
                new DefineDepartmentCategory(
                        TEST_DEPT_CODE, "Test Beverages", "පරීක්ෂණ බීම", "சோதனை பானங்கள்", null, 10),
                own(FEDERATION));
        updateHandler.handle(
                new UpdateDepartmentCategory(deptId, "Test Beverages", null, null, null, 10, "INACTIVE"),
                own(FEDERATION));

        SkuDetails detailsInactive = new SkuDetails(
                "Inactive Dept Item",
                null,
                null,
                null,
                null,
                null,
                "EA",
                false,
                false,
                false,
                true,
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000100"),
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of(),
                deptId);

        assertThatThrownBy(() -> createSkuHandler.handle(new CreateSku(detailsInactive), own(MPCS)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.department_unknown"));
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
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
