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
                "insert into catalogue.tag (tag_code, name_en, governed, owner_entity_id)"
                        + " values ('t-local-a', 'Mine', false, ?), ('t-local-a2', 'Mine too', false, ?)",
                MPCS_A,
                MPCS_A);
        // V0003: what an entity wrote on the Federation's SHARED item stays its own, except a
        // factory barcode, which everyone may read; the Federation's own rows are everyone's.
        admin.update(
                "insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id)"
                        + " values ('0001', 'INTERNAL', ?, 'EA', ?), ('4791234567913', 'EAN13', ?, 'EA', ?)",
                sharedSku,
                MPCS_A,
                sharedSku,
                MPCS_A);
        admin.update(
                "insert into catalogue.sku_uom_conversion (sku_id, uom_code, factor_to_base, effective_from, owner_entity_id)"
                        + " values (?, 'CASE', 24, date '2026-01-01', ?), (?, 'DOZ', 12, date '2026-01-01', ?)",
                sharedSku,
                FEDERATION,
                sharedSku,
                MPCS_A);
        admin.update(
                "insert into catalogue.sku_tag (sku_id, tag_code, owner_entity_id) values (?, 'core-range', ?), (?, 't-local-a', ?)",
                sharedSku,
                FEDERATION,
                sharedSku,
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
        admin.execute("truncate catalogue.batch, catalogue.batch_key, catalogue.sku_tag, catalogue.sku_barcode,"
                + " catalogue.sku_uom_conversion, catalogue.sku");
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

        // The Federation's code and A's factory code on the SHARED item; not A's INTERNAL code
        // 0001 (unique per owner only: B may have its own 0001), not the code of A's LOCAL item.
        assertThat(seenByB).containsExactlyInAnyOrder("4791234567890", "4791234567913");
        List<String> seenByA = inScope(
                MPCS_A, "OWN", () -> jdbc.queryForList("select barcode from catalogue.sku_barcode", String.class));
        assertThat(seenByA).containsExactlyInAnyOrder("4791234567890", "4791234567906", "0001", "4791234567913");
    }

    @Test
    void theConversionsAndTagsOfASharedItemAreReadByEveryoneOnlyWhereItsOwnerWroteThem() {
        List<String> conversionsForB = inScope(
                MPCS_B,
                "OWN",
                () -> jdbc.queryForList("select uom_code from catalogue.sku_uom_conversion", String.class));
        List<String> tagsForB =
                inScope(MPCS_B, "OWN", () -> jdbc.queryForList("select tag_code from catalogue.sku_tag", String.class));

        assertThat(conversionsForB).as("the Federation's CASE, not A's DOZ").containsExactly("CASE");
        assertThat(tagsForB)
                .as("the Federation's governed tag, not A's local tag")
                .containsExactly("core-range");
        assertThat(inScope(
                        MPCS_A, "OWN", () -> jdbc.queryForList("select tag_code from catalogue.sku_tag", String.class)))
                .containsExactlyInAnyOrder("core-range", "t-local-a");
    }

    @Test
    void aChildRowMayBeWrittenOnlyOnAnItemTheCallerOwns() {
        // V0003: own_write on the child tables asks who owns the parent SKU.
        assertThat(inScope(MPCS_A, "OWN", () -> insertConversion(localSkuOfA, MPCS_A)))
                .isEqualTo(1);
        assertThatThrownBy(() -> inScope(MPCS_A, "OWN", () -> insertConversion(sharedSku, MPCS_A)))
                .as("a conversion on the Federation's SHARED item")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> inScope(MPCS_A, "OWN", () -> insertConversion(localSkuOfB, MPCS_A)))
                .as("a conversion on B's LOCAL item")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void anEntityRegistersAFactoryBarcodeOnASharedItemButKeepsInternalCodesToItsOwnItems() {
        // 22A section 6, RegisterBarcode: "INTERNAL only for own SKUs".
        assertThat(inScope(MPCS_B, "OWN", () -> insertBarcode("4791234567920", "EAN13", sharedSku, MPCS_B)))
                .isEqualTo(1);
        assertThat(inScope(MPCS_B, "OWN", () -> insertBarcode("0002", "INTERNAL", localSkuOfB, MPCS_B)))
                .isEqualTo(1);
        assertThatThrownBy(() -> inScope(MPCS_B, "OWN", () -> insertBarcode("0003", "INTERNAL", sharedSku, MPCS_B)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() ->
                        inScope(MPCS_B, "OWN", () -> insertBarcode("4791234567937", "EAN13", localSkuOfA, MPCS_B)))
                .as("a factory code on another entity's LOCAL item")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void anEntityTagsASharedItemWithItsOwnLocalTagsOnly() {
        // 22A section 6, TagSku: "governed tags on SHARED only by F"; doc 22 section 3.5, local
        // tags are the entity's own merchandising.
        assertThat(inScope(MPCS_A, "OWN", () -> insertTag(localSkuOfA, "core-range", MPCS_A)))
                .as("a governed tag on its own LOCAL item")
                .isEqualTo(1);
        assertThat(inScope(MPCS_A, "OWN", () -> insertTag(sharedSku, "t-local-a2", MPCS_A)))
                .as("its own local tag on the SHARED item")
                .isEqualTo(1);
        assertThatThrownBy(() -> inScope(MPCS_A, "OWN", () -> insertTag(sharedSku, "rice", MPCS_A)))
                .as("a governed tag on the SHARED item")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> inScope(MPCS_A, "OWN", () -> insertTag(localSkuOfB, "t-local-a2", MPCS_A)))
                .as("a local tag on B's LOCAL item")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void theSameBatchCannotBeRegisteredTwiceAndACorrectionKeepsItsIdentity() {
        // V0003: catalogue.batch_key, one identity per (sku, supplier, batch_no), whatever the
        // partition and whoever registers it.
        assertThatThrownBy(
                        () -> inScope(MPCS_B, "OWN", () -> insertBatch(Ids.next(), sharedSku, "B2411A", null, MPCS_B)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("batch_key_identity_uq");

        UUID replacement = Ids.next();
        UUID pointedAt = inScope(MPCS_A, "OWN", () -> {
            insertBatch(replacement, sharedSku, "B2411A", batchOfA, MPCS_A);
            return jdbc.queryForObject(
                    "select batch_id from catalogue.batch_key where sku_id = ? and batch_no = 'B2411A'",
                    UUID.class,
                    sharedSku);
        });
        assertThat(pointedAt).isEqualTo(replacement);

        // Another entity correcting A's batch changes no identity row, so the correction is refused
        // (M2-05 settles the lot holder's correction, 22A section 6).
        assertThatThrownBy(() ->
                        inScope(MPCS_B, "OWN", () -> insertBatch(Ids.next(), sharedSku, "B2411A", batchOfA, MPCS_B)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("cannot change");
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

    private int insertConversion(UUID skuId, UUID owner) {
        return jdbc.update(
                "insert into catalogue.sku_uom_conversion (sku_id, uom_code, factor_to_base, effective_from, owner_entity_id)"
                        + " values (?, 'PKT', 6, date '2026-06-01', ?)",
                skuId,
                owner);
    }

    private int insertBarcode(String barcode, String symbology, UUID skuId, UUID owner) {
        return jdbc.update(
                "insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id)"
                        + " values (?, ?, ?, 'EA', ?)",
                barcode,
                symbology,
                skuId,
                owner);
    }

    private int insertTag(UUID skuId, String tagCode, UUID owner) {
        return jdbc.update(
                "insert into catalogue.sku_tag (sku_id, tag_code, owner_entity_id) values (?, ?, ?)",
                skuId,
                tagCode,
                owner);
    }

    private int insertBatch(UUID batchId, UUID skuId, String batchNo, UUID corrects, UUID owner) {
        return jdbc.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, printed_mrp, corrects_batch_id, owner_entity_id)"
                        + " values (?, ?, ?, 1080.00, ?, ?)",
                batchId,
                skuId,
                batchNo,
                corrects,
                owner);
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
