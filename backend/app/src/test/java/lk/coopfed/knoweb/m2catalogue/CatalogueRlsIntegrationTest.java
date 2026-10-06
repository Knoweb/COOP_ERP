package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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

    // The Federation the database names (kernel.system_entity(), kernel V0061): only its SKUs
    // are SHARED to everyone (m2catalogue V0006).
    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID MPCS_A = UUID.fromString("00000000-0000-0000-0000-0000000002a1");
    private static final UUID MPCS_B = UUID.fromString("00000000-0000-0000-0000-0000000002b1");
    private static final UUID TAX_CATEGORY = UUID.fromString("00000000-0000-0000-0000-0000000002c1");

    /** The codes of the tag assignments the caller reads, joined to the tags it reads (V0007). */
    private static final String TAGS_ASSIGNED =
            "select t.tag_code from catalogue.sku_tag st join catalogue.tag t on t.tag_id = st.tag_id";

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
                "insert into catalogue.tag (tag_id, tag_code, name_en, governed, owner_entity_id)"
                        + " values (?, 't-local-a', 'Mine', false, ?), (?, 't-local-a2', 'Mine too', false, ?)",
                Ids.next(),
                MPCS_A,
                Ids.next(),
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
                "insert into catalogue.sku_tag (sku_id, tag_id, owner_entity_id) values"
                        + " (?, (select tag_id from catalogue.tag where tag_code = 'core-range' and governed), ?),"
                        + " (?, (select tag_id from catalogue.tag where tag_code = 't-local-a'), ?)",
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
        admin.execute(
                "truncate catalogue.batch, catalogue.batch_key, catalogue.supplier, catalogue.sku_tag, catalogue.sku_barcode, catalogue.sku_image,"
                        + " catalogue.sku_uom_conversion, catalogue.sku");
        admin.update("delete from inventory.stock_lot where owner_entity_id = ?", MPCS_B);
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
        List<String> tagsForB = inScope(MPCS_B, "OWN", () -> jdbc.queryForList(TAGS_ASSIGNED, String.class));

        assertThat(conversionsForB).as("the Federation's CASE, not A's DOZ").containsExactly("CASE");
        assertThat(tagsForB)
                .as("the Federation's governed tag, not A's local tag")
                .containsExactly("core-range");
        assertThat(inScope(MPCS_A, "OWN", () -> jdbc.queryForList(TAGS_ASSIGNED, String.class)))
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
    void twoEntitiesDefineTheSameLocalCodeAndTheFederationAGovernedOneBesideThem() {
        // CR-22A-1 (V0007): the key is the code within its owner. A's "t-local-a" is in place
        // (arrange), and B, which cannot see it, defines its own without meeting A's key.
        assertThat(inScope(MPCS_B, "OWN", () -> insertLocalTag(Ids.next(), "t-local-a", MPCS_B)))
                .isEqualTo(1);
        assertThat(inScope(MPCS_B, "OWN", () -> count("catalogue.tag where tag_code = 't-local-a'")))
                .as("A's tag stays hidden from B")
                .isZero();
        assertThat(inScope(FEDERATION, "OWN", () -> insertGovernedTag(Ids.next(), "t-local-a")))
                .as("a governed code an entity took first as a local one")
                .isEqualTo(1);

        // Within one owner, and among governed tags, the code is still unique.
        assertThatThrownBy(() -> inScope(MPCS_A, "OWN", () -> insertLocalTag(Ids.next(), "t-local-a", MPCS_A)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("tag_code_per_owner");
        assertThatThrownBy(() -> inScope(FEDERATION, "OWN", () -> insertGovernedTag(Ids.next(), "core-range")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("tag_code_per_owner");
    }

    @Test
    void aGovernedTagIsWrittenByTheFederationOnly() {
        // 22A section 6, DefineTag: "governed tags F only" (V0007 governed_write, governed_update).
        assertThatThrownBy(() -> inScope(MPCS_A, "OWN", () -> insertGovernedTag(Ids.next(), "t-gov")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> inScope(FEDERATION, "FEDERATION_VIEW", () -> insertGovernedTag(Ids.next(), "t-gov")))
                .as("the Federation's read-only view writes nothing")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThat(inScope(FEDERATION, "OWN", () -> insertGovernedTag(Ids.next(), "t-gov")))
                .isEqualTo(1);

        superuserJdbc()
                .update(
                        "insert into catalogue.tag (tag_id, tag_code, name_en, governed) values (?, 't-gov', 'Gov', true)",
                        Ids.next());
        String rename = "update catalogue.tag set name_en = 'Renamed' where tag_code = 't-gov'";
        assertThat(inScope(MPCS_A, "OWN", () -> jdbc.update(rename))).isZero();
        assertThat(inScope(FEDERATION, "OWN", () -> jdbc.update(rename))).isEqualTo(1);
        assertThatThrownBy(() -> inScope(
                        FEDERATION,
                        "OWN",
                        () -> jdbc.update(
                                "update catalogue.tag set governed = false, owner_entity_id = ? where tag_code = 't-gov'",
                                MPCS_A)))
                .as("a governed tag is not handed to an entity")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void anEntityCannotPutAnotherEntitysLocalTagOnItsItem() {
        // The foreign key finds the tag without row-level security; own_write (V0007) asks for a
        // governed tag or the caller's own.
        UUID tagOfB = Ids.next();
        superuserJdbc()
                .update(
                        "insert into catalogue.tag (tag_id, tag_code, name_en, governed, owner_entity_id)"
                                + " values (?, 't-local-b', 'B', false, ?)",
                        tagOfB,
                        MPCS_B);

        assertThatThrownBy(() -> inScope(
                        MPCS_A,
                        "OWN",
                        () -> jdbc.update(
                                "insert into catalogue.sku_tag (sku_id, tag_id, owner_entity_id) values (?, ?, ?)",
                                localSkuOfA,
                                tagOfB,
                                MPCS_A)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThat(inScope(MPCS_B, "OWN", () -> insertTag(localSkuOfB, "t-local-b", MPCS_B)))
                .as("B's own tag on B's item")
                .isEqualTo(1);
    }

    @Test
    void aSupplierIsReadByOthersOnlyOnceABatchCitesIt() {
        // Decided 27 September 2026 (V0007 cited_read): the owner reads its suppliers, everyone
        // else the supplier of a batch, which is global identity (doc 22 section 3.7, B-03).
        UUID supplier = Ids.next();
        superuserJdbc()
                .update(
                        "insert into catalogue.supplier (supplier_id, owner_entity_id, name) values (?, ?, 'Acme')",
                        supplier,
                        MPCS_A);
        String read = "catalogue.supplier where supplier_id = '" + supplier + "'";

        assertThat(inScope(MPCS_A, "OWN", () -> count(read))).isEqualTo(1);
        assertThat(inScope(MPCS_B, "OWN", () -> count(read)))
                .as("nobody's batch cites it yet")
                .isZero();
        assertThat(inScope(FEDERATION, "FEDERATION_VIEW", () -> count(read))).isEqualTo(1);

        superuserJdbc()
                .update(
                        "insert into catalogue.batch (batch_id, sku_id, supplier_id, batch_no, printed_mrp, owner_entity_id)"
                                + " values (?, ?, ?, 'B-ACME-1', 500.00, ?)",
                        Ids.next(),
                        sharedSku,
                        supplier,
                        MPCS_A);

        assertThat(inScope(MPCS_B, "OWN", () -> count(read)))
                .as("the supplier of a batch B can read")
                .isEqualTo(1);
        assertThat(inScope(null, "NONE", () -> count(read))).isZero();
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
    }

    @Test
    void anotherEntitysCorrectionSupersedesTheBatchAndMovesItsIdentity() {
        // M2-05 (V0004): a lot holder or the Federation corrects a batch it did not register
        // (22A section 6). The trigger batch_correction marks the old row SUPERSEDED and re-points
        // the identity, as the function's owner. Since V0008 (wave 2, RLS-07) the trigger holds the
        // rule itself: the caller is the batch's owner, the Federation or a lot holder, whatever path
        // wrote the correction row; CorrectBatchHandler's guard is no longer the only one.

        // A society that holds no lot of the batch, with the Java guard bypassed (a direct insert):
        // refused by the database, and nothing changed.
        assertThatThrownBy(() ->
                        inScope(MPCS_B, "OWN", () -> insertBatch(Ids.next(), sharedSku, "B2411A", batchOfA, MPCS_B)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("m2.batch.not_holder");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select status from catalogue.batch where batch_id = ?", String.class, batchOfA))
                .isEqualTo("REGISTERED");

        // The owner corrects its own batch; a lot holder corrects the replacement; the Federation
        // corrects that one. Each old row SUPERSEDED, the identity on the newest.
        UUID byOwner = Ids.next();
        committedInScope(MPCS_A, () -> insertBatch(byOwner, sharedSku, "B2411A", batchOfA, MPCS_A));
        superuserJdbc()
                .update(
                        "insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id,"
                                + " qty_on_hand, unit_cost, received_at) values (?, ?, ?, ?, ?, 4, 90, now())",
                        Ids.next(),
                        MPCS_B,
                        Ids.next(),
                        byOwner,
                        sharedSku);
        UUID byHolder = Ids.next();
        committedInScope(MPCS_B, () -> insertBatch(byHolder, sharedSku, "B2411A", byOwner, MPCS_B));
        UUID byFederation = Ids.next();
        committedInScope(FEDERATION, () -> insertBatch(byFederation, sharedSku, "B2411A", byHolder, FEDERATION));

        Map<String, Object> after = superuserJdbc()
                .queryForMap(
                        "select (select status from catalogue.batch where batch_id = ?) as first,"
                                + " (select status from catalogue.batch where batch_id = ?) as second,"
                                + " (select status from catalogue.batch where batch_id = ?) as third,"
                                + " (select batch_id from catalogue.batch_key where sku_id = ? and batch_no = 'B2411A')"
                                + " as identity",
                        batchOfA,
                        byOwner,
                        byHolder,
                        sharedSku);
        assertThat(after.get("first")).isEqualTo("SUPERSEDED");
        assertThat(after.get("second")).isEqualTo("SUPERSEDED");
        assertThat(after.get("third")).isEqualTo("SUPERSEDED");
        assertThat(after.get("identity")).isEqualTo(byFederation);

        // Not in an OWN class, even for the Federation's entity.
        assertThatThrownBy(() -> inScope(
                        FEDERATION,
                        "FEDERATION_VIEW",
                        () -> insertBatch(Ids.next(), sharedSku, "B2411A", byFederation, FEDERATION)))
                .isInstanceOf(DataAccessException.class);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select status from catalogue.batch where batch_id = ?", String.class, byFederation))
                .isEqualTo("REGISTERED");

        // A superseded batch is not corrected again (its replacement is), nor a batch of another item.
        superuserJdbc().update("update catalogue.batch set status = 'SUPERSEDED' where batch_id = ?", batchOfA);
        assertThatThrownBy(() ->
                        inScope(MPCS_B, "OWN", () -> insertBatch(Ids.next(), sharedSku, "B2411A", batchOfA, MPCS_B)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("not a registered batch of the same item");
        assertThatThrownBy(() ->
                        inScope(MPCS_A, "OWN", () -> insertBatch(Ids.next(), localSkuOfA, "B2411A", batchOfA, MPCS_A)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("not a registered batch of the same item");
        // The correction row itself is still the caller's own: nobody writes one for another entity.
        assertThatThrownBy(() ->
                        inScope(MPCS_B, "OWN", () -> insertBatch(Ids.next(), sharedSku, "B2411A", batchOfA, MPCS_A)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void aSupplierIsWrittenByItsOwnerOnlyAndNeverChanged() {
        UUID supplier = Ids.next();
        int inserted = inScope(
                MPCS_A,
                "OWN",
                () -> jdbc.update(
                        "insert into catalogue.supplier (supplier_id, owner_entity_id, name) values (?, ?, 'Acme')",
                        supplier,
                        MPCS_A));
        assertThat(inserted).isEqualTo(1);
        assertThatThrownBy(() -> inScope(
                        MPCS_B,
                        "OWN",
                        () -> jdbc.update(
                                "insert into catalogue.supplier (supplier_id, owner_entity_id, name) values (?, ?, 'X')",
                                Ids.next(),
                                MPCS_A)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() ->
                        inScope(MPCS_A, "OWN", () -> jdbc.update("update catalogue.supplier set name = 'Renamed'")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
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

    /**
     * m2catalogue V0006 (decided 27 September 2026 on the architect's delegation): a SHARED row is
     * everyone's only when the Federation owns it. A society's row marked SHARED behind the
     * handlers (FederationCaller) is read by its owner alone, and so are its children.
     */
    @Test
    void aSocietysRowMarkedSharedIsNotPublished() {
        UUID smuggled = Ids.next();
        insertSku(superuserJdbc(), smuggled, "T-SMUGGLED", MPCS_A, "SHARED");
        superuserJdbc()
                .update(
                        "insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id)"
                                + " values ('4791234567920', 'EAN13', ?, 'EA', ?)",
                        smuggled,
                        MPCS_A);

        assertThat(inScope(MPCS_B, "OWN", this::visibleSkus)).containsExactlyInAnyOrder(sharedSku, localSkuOfB);
        assertThat(inScope(
                        MPCS_B,
                        "OWN",
                        () -> jdbc.queryForList(
                                "select barcode from catalogue.sku_barcode where sku_id = ?", String.class, smuggled)))
                .isEmpty();
        assertThat(inScope(MPCS_A, "OWN", this::visibleSkus)).contains(smuggled);
    }

    @Test
    void onlyTheFederationWritesARowShared() {
        assertThatThrownBy(() -> inScope(MPCS_A, "OWN", () -> insertSku(jdbc, Ids.next(), "T-SH-A", MPCS_A, "SHARED")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> inScope(
                        MPCS_A,
                        "OWN",
                        () -> jdbc.update("update catalogue.sku set status = 'SHARED' where sku_id = ?", localSkuOfA)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");

        int federation = inScope(FEDERATION, "OWN", () -> insertSku(jdbc, Ids.next(), "T-SH-F", FEDERATION, "SHARED"));
        assertThat(federation).isEqualTo(1);
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

    /** Assigns the tag of that code the caller sees (V0007: an assignment names its tag by id). */
    private int insertTag(UUID skuId, String tagCode, UUID owner) {
        return jdbc.update(
                "insert into catalogue.sku_tag (sku_id, tag_id, owner_entity_id)"
                        + " select ?, t.tag_id, ? from catalogue.tag t where t.tag_code = ?",
                skuId,
                owner,
                tagCode);
    }

    private int insertLocalTag(UUID tagId, String tagCode, UUID owner) {
        return jdbc.update(
                "insert into catalogue.tag (tag_id, tag_code, name_en, governed, owner_entity_id)"
                        + " values (?, ?, 'Local', false, ?)",
                tagId,
                tagCode,
                owner);
    }

    private int insertGovernedTag(UUID tagId, String tagCode) {
        return jdbc.update(
                "insert into catalogue.tag (tag_id, tag_code, name_en, governed, owner_entity_id)"
                        + " values (?, ?, 'Governed', true, null)",
                tagId,
                tagCode);
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

    /**
     * The same, committed: for a chain of writes by different entities that the next step must see
     * (the correction chain). The {@code @AfterEach} truncation cleans up.
     */
    private <T> T committedInScope(UUID entityId, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("select set_config('app.scope_entity_id', ?, true)", String.class, entityId.toString());
            jdbc.queryForObject("select set_config('app.scope_location_id', '', true)", String.class);
            jdbc.queryForObject("select set_config('app.scope_class', 'OWN', true)", String.class);
            return work.get();
        });
    }
}
