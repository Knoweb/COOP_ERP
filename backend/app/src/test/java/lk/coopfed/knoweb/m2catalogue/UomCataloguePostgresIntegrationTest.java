package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.DefineUom;
import lk.coopfed.knoweb.m2catalogue.api.UomDefined;
import lk.coopfed.knoweb.m2catalogue.api.UomUpdated;
import lk.coopfed.knoweb.m2catalogue.api.UpdateUom;
import lk.coopfed.knoweb.m2catalogue.internal.unit.DefineUomHandler;
import lk.coopfed.knoweb.m2catalogue.internal.unit.UpdateUomHandler;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.UomView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class UomCataloguePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID MPCS = UUID.fromString("0190e640-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e640-0000-7000-8000-000000000010");

    private static final String TEST_UOM_CODE = "TEST_BOX";

    @Autowired
    private DefineUomHandler defineHandler;

    @Autowired
    private UpdateUomHandler updateHandler;

    @Autowired
    private CatalogueQueries queries;

    @BeforeEach
    void arrange() {
        clean();
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.uom where uom_code = ?", TEST_UOM_CODE);
    }

    @Test
    void federationDefinesAndUpdatesUomAndAudits() {
        // 1. Define Unit of Measure
        String code = defineHandler.handle(
                new DefineUom(
                        TEST_UOM_CODE,
                        "Test Box of 24",
                        "පරීක්ෂණ පෙට්ටිය 24",
                        "சோதனை பெட்டி 24",
                        "24 count outer display carton",
                        false),
                own(FEDERATION));

        assertThat(code).isEqualTo(TEST_UOM_CODE);

        // Verify database row
        List<Map<String, Object>> rows = superuserJdbc()
                .queryForList(
                        "select uom_code, name_en, name_si, name_ta, description, is_weight, status from catalogue.uom where uom_code = ?",
                        TEST_UOM_CODE);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("uom_code")).isEqualTo(TEST_UOM_CODE);
            assertThat(row.get("name_en")).isEqualTo("Test Box of 24");
            assertThat(row.get("name_si")).isEqualTo("පරීක්ෂණ පෙට්ටිය 24");
            assertThat(row.get("name_ta")).isEqualTo("சோதனை பெட்டி 24");
            assertThat(row.get("description")).isEqualTo("24 count outer display carton");
            assertThat(row.get("is_weight")).isEqualTo(false);
            assertThat(row.get("status")).isEqualTo("ACTIVE");
        });

        UUID expectedSubjectId = UUID.nameUUIDFromBytes(TEST_UOM_CODE.getBytes(StandardCharsets.UTF_8));

        // Verify audit and event
        assertThat(audit("UOM_DEFINED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("uom");
            assertThat(record.subject().id()).isEqualTo(expectedSubjectId);
        });
        assertThat(events(UomDefined.class)).singleElement().satisfies(event -> {
            assertThat(event.uomCode()).isEqualTo(TEST_UOM_CODE);
            assertThat(event.nameEn()).isEqualTo("Test Box of 24");
            assertThat(event.weight()).isFalse();
            assertThat(event.status()).isEqualTo("ACTIVE");
        });

        // 2. Both Federation and MPCS can read via CatalogueQueries (authenticated_read RLS)
        List<UomView> fedList = queries.listUoms(false, own(FEDERATION));
        assertThat(fedList).anyMatch(u -> TEST_UOM_CODE.equals(u.uomCode()));

        List<UomView> mpcsList = queries.listUoms(false, own(MPCS));
        assertThat(mpcsList).anyMatch(u -> TEST_UOM_CODE.equals(u.uomCode()));

        // 3. Update Unit of Measure to INACTIVE
        updateHandler.handle(
                new UpdateUom(
                        TEST_UOM_CODE,
                        "Test Box of 24 (Updated)",
                        "පරීක්ෂණ පෙට්ටිය 24",
                        "சோதனை பெட்டி 24",
                        "Updated description",
                        false,
                        "INACTIVE"),
                own(FEDERATION));

        // When listing active only, inactive uom should not appear
        List<UomView> activeOnly = queries.listUoms(false, own(FEDERATION));
        assertThat(activeOnly).noneMatch(u -> TEST_UOM_CODE.equals(u.uomCode()));

        // When listing all, inactive uom should appear
        List<UomView> allUnits = queries.listUoms(true, own(FEDERATION));
        assertThat(allUnits).anyMatch(u -> TEST_UOM_CODE.equals(u.uomCode()));

        // Verify update audit and event
        assertThat(audit("UOM_UPDATED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("uom");
            assertThat(record.subject().id()).isEqualTo(expectedSubjectId);
            assertThat(((Map<?, ?>) record.after()).get("status")).isEqualTo("INACTIVE");
        });
        assertThat(events(UomUpdated.class)).singleElement().satisfies(event -> {
            assertThat(event.uomCode()).isEqualTo(TEST_UOM_CODE);
            assertThat(event.nameEn()).isEqualTo("Test Box of 24 (Updated)");
            assertThat(event.status()).isEqualTo("INACTIVE");
        });
    }

    @Test
    void nonFederationScopeCannotDefineUom() {
        assertThatThrownBy(() -> defineHandler.handle(
                        new DefineUom(TEST_UOM_CODE, "Test Box", null, null, null, false), own(MPCS)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.federation_only"));

        assertThat(audit("UOM_DEFINED")).isEmpty();
        assertThat(events(UomDefined.class)).isEmpty();
    }

    @Test
    void cannotChangeWeightWhenUomIsInUseBySku() {
        // Define active unit
        defineHandler.handle(new DefineUom(TEST_UOM_CODE, "Test Unit", null, null, null, false), own(FEDERATION));

        // Insert SKU using this UOM as base_uom_code
        UUID skuId = UUID.fromString("0190e640-0000-7000-8000-000000000099");
        superuserJdbc()
                .update(
                        """
                insert into catalogue.sku (sku_id, sku_code, owner_entity_id, short_name_en, base_uom_code, tax_category_id, status)
                values (?, 'T-SKU-UOM', ?, 'Test UOM SKU', ?, '00000000-0000-0000-0000-000000000100', 'ACTIVE')
                """,
                        skuId,
                        FEDERATION,
                        TEST_UOM_CODE);

        // Attempt to flip is_weight from false to true
        assertThatThrownBy(() -> updateHandler.handle(
                        new UpdateUom(
                                TEST_UOM_CODE,
                                "Test Unit",
                                null,
                                null,
                                null,
                                true, // changed weight
                                "ACTIVE"),
                        own(FEDERATION)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo("m2.uom.in_use_cannot_change_weight"));
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
