package lk.coopfed.knoweb.m2catalogue.internal.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.internal.sync.SnapshotBuilder;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.ShopRef;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRegistered;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRetired;
import lk.coopfed.knoweb.m2catalogue.api.ConversionDefined;
import lk.coopfed.knoweb.m2catalogue.api.ImageAttached;
import lk.coopfed.knoweb.m2catalogue.api.SkuDeactivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuUpdated;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL integration tests for {@link M2ChangeLogFanOut}: verifies that central SKU, barcode,
 * conversion and image lifecycle events produce the correct change-log records at shops in any
 * status, and that enrolled tills receive expected upserts, overrides, and tombstones in their
 * deltas.
 */
public class M2ChangeLogFanOutIntegrationTest extends PostgresIntegrationTest {

    static final UUID ENTITY_A = UUID.fromString("0190a820-0000-7000-8800-000000000001");
    static final UUID ENTITY_B = UUID.fromString("0190a820-0000-7000-8800-000000000002");
    static final UUID SHOP_A = UUID.fromString("0190a820-0000-7000-8800-000000000101");
    static final UUID SHOP_B = UUID.fromString("0190a820-0000-7000-8800-000000000102");
    static final UUID SHOP_A_ONBOARDING = UUID.fromString("0190a820-0000-7000-8800-000000000103");
    static final UUID SHOP_B_DORMANT = UUID.fromString("0190a820-0000-7000-8800-000000000104");

    static final UUID SKU_SHARED = UUID.fromString("0190a820-0000-7000-8800-000000000301");
    static final UUID SKU_LOCAL_A = UUID.fromString("0190a820-0000-7000-8800-000000000302");
    static final UUID TAX_ID = UUID.fromString("0190a820-0000-7000-8800-000000000901");

    static final String VALID_GTIN = "4791234567891";

    @Autowired
    M2ChangeLogFanOut fanOut;

    @Autowired
    ChangeLog changeLog;

    @Autowired
    SystemScope transactions;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    SnapshotBuilder snapshotBuilder;

    @Autowired
    PartyQueries parties;

    private final ScopeContext centralScope = SystemScope.own(TEST_FEDERATION, null);
    private final ScopeContext tillScopeA = SystemScope.own(ENTITY_A, SHOP_A);
    private final ScopeContext tillScopeB = SystemScope.own(ENTITY_B, SHOP_B);
    private final ScopeContext tillScopeOnboarding = SystemScope.own(ENTITY_A, SHOP_A_ONBOARDING);
    private final ScopeContext tillScopeDormant = SystemScope.own(ENTITY_B, SHOP_B_DORMANT);

    @BeforeEach
    void setupFixtures() {
        JdbcTemplate db = superuserJdbc();

        // Clean up in reverse FK order
        db.update("delete from catalogue.batch_key where sku_id in (?, ?)", SKU_LOCAL_A, SKU_SHARED);
        db.update("delete from catalogue.sku_barcode where sku_id in (?, ?)", SKU_LOCAL_A, SKU_SHARED);
        db.update("delete from catalogue.sku_image where sku_id in (?, ?)", SKU_LOCAL_A, SKU_SHARED);
        db.update("delete from catalogue.sku_uom_conversion where sku_id in (?, ?)", SKU_LOCAL_A, SKU_SHARED);
        db.update("delete from catalogue.sku_tag where sku_id in (?, ?)", SKU_LOCAL_A, SKU_SHARED);
        db.update("delete from catalogue.batch where sku_id in (?, ?)", SKU_LOCAL_A, SKU_SHARED);
        db.update("delete from catalogue.sku where sku_id in (?, ?)", SKU_LOCAL_A, SKU_SHARED);
        db.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX_ID);
        db.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_ID);

        db.update(
                "delete from kernel.change_log where location_id in (?, ?, ?, ?)",
                SHOP_A,
                SHOP_B,
                SHOP_A_ONBOARDING,
                SHOP_B_DORMANT);
        db.update(
                "delete from party.location where location_id in (?, ?, ?, ?)",
                SHOP_A,
                SHOP_B,
                SHOP_A_ONBOARDING,
                SHOP_B_DORMANT);
        db.update("delete from party.entity_party_directory where entity_id in (?, ?)", ENTITY_A, ENTITY_B);
        db.update("delete from party.entity where entity_id in (?, ?)", ENTITY_A, ENTITY_B);

        // Entities
        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) values (?, 'MPCS', 'MPCS-A', 'Society A', 'ACTIVE')",
                ENTITY_A);
        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) values (?, 'MPCS', 'MPCS-B', 'Society B', 'ACTIVE')",
                ENTITY_B);

        // Active shops
        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status)
                values (?, ?, 'SH-A1', 'SHOP', 'Shop A1', 'en', 'ACTIVE')
                """,
                SHOP_A,
                ENTITY_A);
        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status)
                values (?, ?, 'SH-B1', 'SHOP', 'Shop B1', 'en', 'ACTIVE')
                """,
                SHOP_B,
                ENTITY_B);

        // A shop of A not activated yet (its till enrols first) and a dormant shop of B (TD-01)
        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status)
                values (?, ?, 'SH-A2', 'SHOP', 'Shop A2', 'en', 'ONBOARDING')
                """,
                SHOP_A_ONBOARDING,
                ENTITY_A);
        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status)
                values (?, ?, 'SH-B2', 'SHOP', 'Shop B2', 'en', 'DORMANT')
                """,
                SHOP_B_DORMANT,
                ENTITY_B);

        // Shared tax category
        db.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id) values (?, 'TAX-TEST', 'Test Tax', ?)",
                TAX_ID,
                TEST_FEDERATION);

        // SKUs: 1 SHARED, 1 LOCAL to Entity A
        db.update(
                """
                insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,
                                           short_name_ta, base_uom_code, tax_category_id)
                values (?, 'SKU-SHR', ?, 'SHARED', 'Shared Rice 5kg', 'සහල් 5kg', 'அரிசி 5kg', 'EA', ?)
                """,
                SKU_SHARED,
                TEST_FEDERATION,
                TAX_ID);
        db.update(
                """
                insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,
                                           short_name_ta, base_uom_code, tax_category_id)
                values (?, 'SKU-LOC-A', ?, 'LOCAL', 'Local Bread', 'පාන්', 'ரொட்டி', 'EA', ?)
                """,
                SKU_LOCAL_A,
                ENTITY_A,
                TAX_ID);
    }

    @Test
    void centralBarcodeRegistrationReachesEnrolledTillAfterNextDelta() throws Exception {
        // Baseline: Establish till snapshot baseline at V1
        long baselineVersion = initializeTillBaseline(tillScopeA, SHOP_A, SKU_SHARED);

        // Central registers a new GTIN-13 barcode on the SHARED SKU
        superuserJdbc()
                .update(
                        """
                        insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id, status)
                        values (?, 'EAN13', ?, 'EA', ?, 'ACTIVE')
                        """,
                        VALID_GTIN,
                        SKU_SHARED,
                        TEST_FEDERATION);

        BarcodeRegistered event = new BarcodeRegistered(SKU_SHARED, TEST_FEDERATION, VALID_GTIN, "EAN13", "EA", null);
        transactions.inOwnTransaction(centralScope, () -> {
            fanOut.onBarcodeChanged(mapper.valueToTree(event), centralScope);
            return null;
        });

        // Till A fetches its delta
        JsonNode delta = fetchDelta(tillScopeA, baselineVersion);

        // Verify: The updated SKU row is in delta upserts and contains the newly registered barcode
        assertThat(delta.path("tables").path("sku").path("upserts").toString()).contains(SKU_SHARED.toString());
        JsonNode skuRow = findRow(delta.path("tables").path("sku").path("upserts"), SKU_SHARED.toString());
        assertThat(skuRow.isMissingNode()).isFalse();
        assertThat(skuRow.path("data").path("barcodes").toString()).contains(VALID_GTIN);
    }

    @Test
    void skuDeactivationCreatesAppropriateTombstoneInNextDelta() throws Exception {
        long baselineVersion = initializeTillBaseline(tillScopeA, SHOP_A, SKU_SHARED);

        // Central deactivates the SHARED SKU
        superuserJdbc().update("update catalogue.sku set status = 'INACTIVE' where sku_id = ?", SKU_SHARED);

        SkuDeactivated event = new SkuDeactivated(SKU_SHARED, TEST_FEDERATION, "SKU-SHR", "SHARED");
        transactions.inOwnTransaction(centralScope, () -> {
            fanOut.onSkuDeactivated(mapper.valueToTree(event), centralScope);
            return null;
        });

        // Till fetches delta
        JsonNode delta = fetchDelta(tillScopeA, baselineVersion);

        // Verify: SKU is emitted as a tombstone, not an upsert
        assertThat(delta.path("tables").path("sku").path("tombstones").toString())
                .contains(SKU_SHARED.toString());
        assertThat(delta.path("tables").path("sku").path("upserts").toString()).doesNotContain(SKU_SHARED.toString());
    }

    @Test
    void barcodeRetirementRemovesBarcodeFromTillDelta() throws Exception {
        // Register an active barcode first
        superuserJdbc()
                .update(
                        """
                        insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id, status)
                        values (?, 'EAN13', ?, 'EA', ?, 'ACTIVE')
                        """,
                        VALID_GTIN,
                        SKU_SHARED,
                        TEST_FEDERATION);

        long baselineVersion = initializeTillBaseline(tillScopeA, SHOP_A, SKU_SHARED);

        // Central retires the barcode
        superuserJdbc()
                .update(
                        "update catalogue.sku_barcode set status = 'RETIRED' where barcode = ? and sku_id = ?",
                        VALID_GTIN,
                        SKU_SHARED);

        BarcodeRetired event = new BarcodeRetired(SKU_SHARED, TEST_FEDERATION, VALID_GTIN, "EAN13", "EA", null);
        transactions.inOwnTransaction(centralScope, () -> {
            fanOut.onBarcodeChanged(mapper.valueToTree(event), centralScope);
            return null;
        });

        JsonNode delta = fetchDelta(tillScopeA, baselineVersion);

        // The SKU is upserted with its remaining active barcodes (excluding the retired one)
        JsonNode skuRow = findRow(delta.path("tables").path("sku").path("upserts"), SKU_SHARED.toString());
        assertThat(skuRow.isMissingNode()).isFalse();
        assertThat(skuRow.path("data").path("barcodes").toString()).doesNotContain(VALID_GTIN);
    }

    @Test
    void localSkuChangesRemainIsolatedBetweenSocieties() throws Exception {
        long baselineA = initializeTillBaseline(tillScopeA, SHOP_A, SKU_LOCAL_A);
        long baselineB = initializeTillBaseline(tillScopeB, SHOP_B, SKU_SHARED);

        // Society A updates its LOCAL SKU
        superuserJdbc()
                .update("update catalogue.sku set short_name_en = 'Special Local Bread' where sku_id = ?", SKU_LOCAL_A);

        SkuUpdated event = new SkuUpdated(SKU_LOCAL_A, ENTITY_A, "SKU-LOC-A", "LOCAL");
        ScopeContext scopeA = SystemScope.own(ENTITY_A, null);
        transactions.inOwnTransaction(scopeA, () -> {
            fanOut.onSkuChanged(mapper.valueToTree(event), scopeA);
            return null;
        });

        // Shop A receives the delta
        JsonNode deltaA = fetchDelta(tillScopeA, baselineA);
        assertThat(deltaA.path("tables").path("sku").path("upserts").toString()).contains(SKU_LOCAL_A.toString());

        // Shop B has NO change for SKU_LOCAL_A (isolated)
        JsonNode deltaB = fetchDelta(tillScopeB, baselineB);
        assertThat(deltaB.path("tables").path("sku").path("upserts").toString()).doesNotContain(SKU_LOCAL_A.toString());
        assertThat(deltaB.path("tables").path("sku").path("tombstones").toString())
                .doesNotContain(SKU_LOCAL_A.toString());
    }

    @Test
    void sharedSkuChangesReachAllEligibleShops() throws Exception {
        long baselineA = initializeTillBaseline(tillScopeA, SHOP_A, SKU_SHARED);
        long baselineB = initializeTillBaseline(tillScopeB, SHOP_B, SKU_SHARED);

        superuserJdbc()
                .update("update catalogue.sku set short_name_en = 'Updated Shared Rice' where sku_id = ?", SKU_SHARED);

        SkuUpdated event = new SkuUpdated(SKU_SHARED, TEST_FEDERATION, "SKU-SHR", "SHARED");
        transactions.inOwnTransaction(centralScope, () -> {
            fanOut.onSkuChanged(mapper.valueToTree(event), centralScope);
            return null;
        });

        // Both Shop A and Shop B receive the update
        JsonNode deltaA = fetchDelta(tillScopeA, baselineA);
        assertThat(deltaA.path("tables").path("sku").path("upserts").toString()).contains(SKU_SHARED.toString());

        JsonNode deltaB = fetchDelta(tillScopeB, baselineB);
        assertThat(deltaB.path("tables").path("sku").path("upserts").toString()).contains(SKU_SHARED.toString());
    }

    @Test
    void conversionDefinedPropagatesToTillsInNextDelta() throws Exception {
        long baselineVersion = initializeTillBaseline(tillScopeA, SHOP_A, SKU_SHARED);

        superuserJdbc()
                .update(
                        """
                        insert into catalogue.sku_uom_conversion (sku_id, uom_code, factor_to_base, effective_from, owner_entity_id)
                        values (?, 'BAG', 5.0, '2026-01-01', ?)
                        """,
                        SKU_SHARED,
                        TEST_FEDERATION);

        ConversionDefined event = new ConversionDefined(
                SKU_SHARED, TEST_FEDERATION, "BAG", new BigDecimal("5.0"), LocalDate.of(2026, 1, 1), null);
        transactions.inOwnTransaction(centralScope, () -> {
            fanOut.onConversionChanged(mapper.valueToTree(event), centralScope);
            return null;
        });

        JsonNode delta = fetchDelta(tillScopeA, baselineVersion);
        JsonNode skuRow = findRow(delta.path("tables").path("sku").path("upserts"), SKU_SHARED.toString());
        assertThat(skuRow.path("data").path("conversions").toString()).contains("BAG");
    }

    @Test
    void localImageOverrideRemainsEntityScoped() throws Exception {
        long baselineA = initializeTillBaseline(tillScopeA, SHOP_A, SKU_SHARED);
        long baselineB = initializeTillBaseline(tillScopeB, SHOP_B, SKU_SHARED);

        // Society A attaches a local image override to the SHARED SKU
        superuserJdbc()
                .update(
                        """
                        insert into catalogue.sku_image (image_id, sku_id, owner_entity_id, object_key_full,
                                                         object_key_thumb, content_hash, content_type, status, upload_expires_at)
                        values (gen_random_uuid(), ?, ?, 'objects/m2/full.png', 'objects/m2/thumb_a.png',
                                repeat('b', 64), 'image/png', 'ACTIVE', now())
                        """,
                        SKU_SHARED,
                        ENTITY_A);

        ImageAttached event =
                new ImageAttached(UUID.randomUUID(), SKU_SHARED, null, ENTITY_A, "objects/m2/thumb_a.png", null);
        ScopeContext scopeA = SystemScope.own(ENTITY_A, null);
        transactions.inOwnTransaction(scopeA, () -> {
            fanOut.onImageChanged(mapper.valueToTree(event), scopeA);
            return null;
        });

        // Shop A receives the override
        JsonNode deltaA = fetchDelta(tillScopeA, baselineA);
        assertThat(deltaA.path("tables").path("sku").path("upserts").toString()).contains(SKU_SHARED.toString());
        JsonNode skuA = findRow(deltaA.path("tables").path("sku").path("upserts"), SKU_SHARED.toString());
        assertThat(skuA.path("data").path("images").toString()).contains("objects/m2/thumb_a.png");

        // Shop B of another society does NOT receive the change log entry or override
        JsonNode deltaB = fetchDelta(tillScopeB, baselineB);
        assertThat(deltaB.path("tables").path("sku").path("upserts").toString()).doesNotContain(SKU_SHARED.toString());
    }

    /**
     * TD-01: a till enrols before its shop is activated, and a dormant shop's till keeps its
     * version, so a catalogue change is recorded at a shop in any status, not only ACTIVE ones.
     */
    @Test
    void catalogueChangesReachShopsThatAreNotActiveYetOrDormant() throws Exception {
        long baselineOnboarding = initializeTillBaseline(tillScopeOnboarding, SHOP_A_ONBOARDING, SKU_SHARED);
        long baselineDormant = initializeTillBaseline(tillScopeDormant, SHOP_B_DORMANT, SKU_SHARED);

        superuserJdbc()
                .update("update catalogue.sku set short_name_en = 'Rice before opening' where sku_id = ?", SKU_SHARED);
        SkuUpdated shared = new SkuUpdated(SKU_SHARED, TEST_FEDERATION, "SKU-SHR", "SHARED");
        transactions.inOwnTransaction(centralScope, () -> {
            fanOut.onSkuChanged(mapper.valueToTree(shared), centralScope);
            return null;
        });

        assertThat(fetchDelta(tillScopeOnboarding, baselineOnboarding)
                        .path("tables")
                        .path("sku")
                        .path("upserts")
                        .toString())
                .as("the ONBOARDING shop's till")
                .contains(SKU_SHARED.toString());
        assertThat(fetchDelta(tillScopeDormant, baselineDormant)
                        .path("tables")
                        .path("sku")
                        .path("upserts")
                        .toString())
                .as("the DORMANT shop's till")
                .contains(SKU_SHARED.toString());

        // A LOCAL item of society A reaches its ONBOARDING shop as well (the entity's shops).
        long afterShared = fetchDelta(tillScopeOnboarding, 0).path("version").asLong();
        SkuUpdated local = new SkuUpdated(SKU_LOCAL_A, ENTITY_A, "SKU-LOC-A", "LOCAL");
        ScopeContext scopeA = SystemScope.own(ENTITY_A, null);
        transactions.inOwnTransaction(scopeA, () -> {
            fanOut.onSkuChanged(mapper.valueToTree(local), scopeA);
            return null;
        });
        assertThat(fetchDelta(tillScopeOnboarding, afterShared)
                        .path("tables")
                        .path("sku")
                        .path("upserts")
                        .toString())
                .contains(SKU_LOCAL_A.toString());
    }

    /**
     * TD-03: the shops come from M1's {@code party.change_log_shops} (m1party V0017), which decides
     * from the caller's scope: the Federation lists every shop, an entity its own shops only (a
     * location in its scope does not narrow that), and no other class gets any.
     */
    @Test
    void onlyTheFederationListsEveryShopAndAnEntityOnlyItsOwn() {
        ScopeContext scopeA = SystemScope.own(ENTITY_A, null);

        List<ShopRef> every =
                transactions.inOwnTransaction(centralScope, () -> parties.listChangeLogShops(null, centralScope));
        assertThat(every)
                .contains(
                        new ShopRef(ENTITY_A, SHOP_A),
                        new ShopRef(ENTITY_A, SHOP_A_ONBOARDING),
                        new ShopRef(ENTITY_B, SHOP_B),
                        new ShopRef(ENTITY_B, SHOP_B_DORMANT));
        assertThat(transactions.inOwnTransaction(
                        centralScope, () -> parties.listChangeLogShops(ENTITY_B, centralScope)))
                .containsExactlyInAnyOrder(new ShopRef(ENTITY_B, SHOP_B), new ShopRef(ENTITY_B, SHOP_B_DORMANT));

        assertThat(transactions.inOwnTransaction(scopeA, () -> parties.listChangeLogShops(ENTITY_A, scopeA)))
                .containsExactlyInAnyOrder(new ShopRef(ENTITY_A, SHOP_A), new ShopRef(ENTITY_A, SHOP_A_ONBOARDING));
        assertThat(transactions.inOwnTransaction(tillScopeA, () -> parties.listChangeLogShops(ENTITY_A, tillScopeA)))
                .as("a location in the scope does not narrow it")
                .containsExactlyInAnyOrder(new ShopRef(ENTITY_A, SHOP_A), new ShopRef(ENTITY_A, SHOP_A_ONBOARDING));

        assertThat(transactions.inOwnTransaction(scopeA, () -> parties.listChangeLogShops(null, scopeA)))
                .as("a society does not list the federation's shops")
                .isEmpty();
        assertThat(transactions.inOwnTransaction(scopeA, () -> parties.listChangeLogShops(ENTITY_B, scopeA)))
                .as("nor another society's")
                .isEmpty();
        ScopeContext fedView = SystemScope.federationView();
        assertThat(transactions.inOwnTransaction(fedView, () -> parties.listChangeLogShops(null, fedView)))
                .as("only an OWN scope is answered")
                .isEmpty();
    }

    /**
     * TD-02 and TD-04: M2 serves no batch table and records no batch change; the batches a till
     * holds are M5's lots with on-hand at the location (doc 32 section 5.1).
     */
    @Test
    void aFullSnapshotCarriesNoBatchesOfTheFederation() throws Exception {
        superuserJdbc()
                .update(
                        """
                        insert into catalogue.batch (batch_id, sku_id, batch_no, owner_entity_id, status,
                                                     printed_mrp, expiry_date, created_at)
                        values (?, ?, 'B-TEST-01', ?, 'REGISTERED', 450.00, '2027-06-30', clock_timestamp())
                        """,
                        UUID.fromString("0190a820-0000-7000-8800-000000000501"),
                        SKU_SHARED,
                        TEST_FEDERATION);

        JsonNode full = fetchDelta(tillScopeA, 0);

        assertThat(full.path("full").asBoolean()).isTrue();
        assertThat(full.path("tables").has("sku")).isTrue();
        assertThat(full.path("tables").has("batch")).isFalse();
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private long initializeTillBaseline(ScopeContext tillScope, UUID shopId, UUID initialSku) {
        transactions.inOwnTransaction(centralScope, () -> {
            changeLog.append(
                    List.of(new Target(tillScope.entityId(), shopId)),
                    List.of(Change.upsert("sku", initialSku)),
                    null,
                    false,
                    centralScope);
            return null;
        });
        return mapper.valueToTree(transactions.inOwnTransaction(
                        tillScope, () -> (Object) snapshotBuilder.build(tillScope, 0, Duration.ofDays(30))))
                .path("version")
                .asLong();
    }

    private JsonNode fetchDelta(ScopeContext tillScope, long sinceVersion) {
        return mapper.valueToTree(transactions.inOwnTransaction(
                tillScope, () -> (Object) snapshotBuilder.build(tillScope, sinceVersion, Duration.ofDays(30))));
    }

    private static JsonNode findRow(JsonNode rowsArray, String rowId) {
        if (rowsArray.isArray()) {
            for (JsonNode row : rowsArray) {
                if (rowId.equals(row.path("rowId").asText())) {
                    return row;
                }
            }
        }
        return mapperMissing();
    }

    private static JsonNode mapperMissing() {
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
