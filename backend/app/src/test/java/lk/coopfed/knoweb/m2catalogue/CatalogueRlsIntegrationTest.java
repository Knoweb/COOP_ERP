package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rows of the RLS matrix of 22A section 9 for the tables of m2catalogue V0001: "SHARED visible
 * to all; LOCAL OWN plus Federation view; batches readable by all, writable by registering
 * module". The fixtures are written by the superuser; every read and write under test goes
 * through the application's connection (coop_app, a member of app_rw) with the scope set on
 * the transaction, the way M1RlsIntegrationTest does it. The handlers of M2-02 onwards prove
 * the same through the kernel's scope.
 */
class CatalogueRlsIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = UUID.fromString("00000000-0000-0000-0000-0000000002f1");
    private static final UUID MPCS_A = UUID.fromString("00000000-0000-0000-0000-0000000002a1");
    private static final UUID MPCS_B = UUID.fromString("00000000-0000-0000-0000-0000000002b1");
    private static final UUID TAX_CATEGORY = UUID.fromString("00000000-0000-0000-0000-0000000002c1");

    private final UUID sharedSku = Ids.next();
    private final UUID localSkuOfA = Ids.next();
    private final UUID localSkuOfB = Ids.next();
    private final UUID batchOfA = Ids.next();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        insertSku(admin, sharedSku, "T-SHARED", FEDERATION, "SHARED");
        insertSku(admin, localSkuOfA, "T-LOCAL-A", MPCS_A, "LOCAL");
        insertSku(admin, localSkuOfB, "T-LOCAL-B", MPCS_B, "LOCAL");
        admin.update(
                "insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id)"
                        + " values ('4791234567890', 'EAN13', ?, 'EA', ?), ('4791234567906', 'EAN13', ?, 'EA', ?)",
                sharedSku,
                FEDERATION,
                localSkuOfA,
                MPCS_A);
        admin.update(
                "insert into catalogue.tag (tag_code, name_en, governed, owner_entity_id) values ('t-local-a', 'Mine', false, ?)",
                MPCS_A);
        admin.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, printed_mrp, owner_entity_id)"
                        + " values (?, ?, 'B2411A', 1080.00, ?)",
                batchOfA,
                sharedSku,
                MPCS_A);
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'T-RLS', 'Test category', ?)",
                TAX_CATEGORY,
                FEDERATION);
        admin.update(
                "insert into catalogue.tax_rate (tax_category_id, rate_percent, effective_from, owner_entity_id)"
                        + " values (?, 18.00, date '2024-01-01', ?)",
                TAX_CATEGORY,
                FEDERATION);
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute(
                "truncate catalogue.batch, catalogue.sku_tag, catalogue.sku_barcode, catalogue.sku_uom_conversion,"
                        + " catalogue.sku");
        admin.update("delete from catalogue.tag where tag_code like 't-%'");
        admin.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX_CATEGORY);
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void anEntityReadsItsOwnItemsAndEverySharedItemButNotAnotherEntitysLocalItems() {
        assertThat(inScope(MPCS_A, "OWN", this::visibleSkus)).containsExactlyInAnyOrder(sharedSku, localSkuOfA);
        assertThat(inScope(MPCS_B, "OWN", this::visibleSkus)).containsExactlyInAnyOrder(sharedSku, localSkuOfB);
    }

    @Test
    void theFederationViewReadsEveryItem() {
        assertThat(inScope(MPCS_A, "FEDERATION_VIEW", this::visibleSkus))
                .containsExactlyInAnyOrder(sharedSku, localSkuOfA, localSkuOfB);
    }

    @Test
    void anExternalScopeReadsTheSharedItemsAndTheItemsOfItsGrantOnly() {
        List<UUID> visible = inScope(MPCS_A, "EXTERNAL_TIMEBOXED", () -> {
            jdbc.queryForObject("select set_config('app.granted_entities', ?, true)", String.class, "{" + MPCS_B + "}");
            return visibleSkus();
        });

        assertThat(visible).containsExactlyInAnyOrder(sharedSku, localSkuOfB);
    }

    @Test
    void aTransactionWithoutAScopeReadsNothingAtAll() {
        assertThat(inScope(null, "NONE", this::visibleSkus)).isEmpty();
        assertThat(inScope(null, "NONE", () -> count("catalogue.uom"))).isZero();
        assertThat(inScope(null, "NONE", () -> count("catalogue.batch"))).isZero();
        assertThat(inScope(null, "NONE", () -> count("catalogue.tax_rate"))).isZero();
        assertThat(inScope(null, "NONE", () -> count("catalogue.tag"))).isZero();
    }

    @Test
    void theBarcodesOfASharedItemAreReadByEveryoneThoseOfALocalItemByItsOwnerOnly() {
        List<String> seenByB = inScope(
                MPCS_B, "OWN", () -> jdbc.queryForList("select barcode from catalogue.sku_barcode", String.class));

        assertThat(seenByB).containsExactly("4791234567890");
    }

    @Test
    void referenceDataIsReadByEveryScope() {
        assertThat(inScope(MPCS_B, "OWN", () -> count("catalogue.uom"))).isGreaterThanOrEqualTo(10);
        assertThat(inScope(
                        MPCS_B,
                        "OWN",
                        () -> count("catalogue.tax_rate where tax_category_id = '" + TAX_CATEGORY + "'")))
                .isEqualTo(1);
        List<String> tagsOfB =
                inScope(MPCS_B, "OWN", () -> jdbc.queryForList("select tag_code from catalogue.tag", String.class));
        assertThat(tagsOfB).contains("core-range").doesNotContain("t-local-a");
    }

    @Test
    void aBatchIsReadByEveryScopeAndChangedByItsRegisteringEntityOnly() {
        assertThat(inScope(MPCS_B, "OWN", () -> count("catalogue.batch"))).isEqualTo(1);

        int changedByB = inScope(
                MPCS_B,
                "OWN",
                () -> jdbc.update("update catalogue.batch set status = 'SUPERSEDED' where batch_id = ?", batchOfA));
        assertThat(changedByB).isZero();

        int changedByA = inScope(
                MPCS_A,
                "OWN",
                () -> jdbc.update("update catalogue.batch set status = 'SUPERSEDED' where batch_id = ?", batchOfA));
        assertThat(changedByA).isEqualTo(1);
    }

    @Test
    void aPrintedMrpIsNeverEditedEvenByTheRegisteringEntity() {
        assertThatThrownBy(() -> inScope(
                        MPCS_A,
                        "OWN",
                        () -> jdbc.update(
                                "update catalogue.batch set printed_mrp = 1.00 where batch_id = ?", batchOfA)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void anEntityRegistersItsOwnItemButNotOneForAnotherEntity() {
        int inserted = inScope(MPCS_A, "OWN", () -> insertSku(jdbc, Ids.next(), "T-NEW-A", MPCS_A, "DRAFT"));
        assertThat(inserted).isEqualTo(1);

        assertThatThrownBy(() -> inScope(MPCS_A, "OWN", () -> insertSku(jdbc, Ids.next(), "T-NEW-B", MPCS_B, "DRAFT")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void theFederationViewWritesNothing() {
        assertThatThrownBy(() ->
                        inScope(MPCS_A, "FEDERATION_VIEW", () -> insertSku(jdbc, Ids.next(), "T-FV", MPCS_A, "DRAFT")))
                .isInstanceOf(DataAccessException.class);

        int changed = inScope(
                MPCS_A,
                "FEDERATION_VIEW",
                () -> jdbc.update("update catalogue.sku set status = 'INACTIVE' where sku_id = ?", localSkuOfA));
        assertThat(changed).isZero();
    }

    @Test
    void anEntityChangesItsOwnItemButNotAnotherEntitys() {
        int own = inScope(
                MPCS_A,
                "OWN",
                () -> jdbc.update("update catalogue.sku set status = 'INACTIVE' where sku_id = ?", localSkuOfA));
        int other = inScope(
                MPCS_A,
                "OWN",
                () -> jdbc.update("update catalogue.sku set status = 'INACTIVE' where sku_id = ?", localSkuOfB));

        assertThat(own).isEqualTo(1);
        assertThat(other).isZero();
    }

    @Test
    void theApplicationCannotAddAUnit() {
        assertThatThrownBy(() -> inScope(
                        FEDERATION,
                        "OWN",
                        () -> jdbc.update(
                                "insert into catalogue.uom (uom_code, name_en) values ('ZZ', 'Not allowed')")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    private List<UUID> visibleSkus() {
        return jdbc.queryForList("select sku_id from catalogue.sku", UUID.class);
    }

    private int count(String from) {
        return jdbc.queryForObject("select count(*) from " + from, Integer.class);
    }

    private static int insertSku(JdbcTemplate db, UUID skuId, String code, UUID owner, String status) {
        // A SHARED row needs the three names (B-I1).
        return db.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, tax_category_id) values (?, ?, ?, ?, ?, ?, ?, 'EA', ?)",
                skuId,
                code,
                owner,
                status,
                "Milk powder 400g",
                "කිරිපිටි 400g",
                "பால் மா 400g",
                TAX_CATEGORY);
    }

    /** Runs the work in a transaction with the scope set as the kernel sets it; always rolled back. */
    private <T> T inScope(UUID entityId, String policyClass, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject(
                    "select set_config('app.scope_entity_id', ?, true)",
                    String.class,
                    entityId == null ? "" : entityId.toString());
            jdbc.queryForObject("select set_config('app.scope_location_id', '', true)", String.class);
            jdbc.queryForObject("select set_config('app.scope_class', ?, true)", String.class, policyClass);
            try {
                return work.get();
            } finally {
                status.setRollbackOnly();
            }
        });
    }
}
