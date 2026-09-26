package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeLinked;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRegistered;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRetired;
import lk.coopfed.knoweb.m2catalogue.api.LinkBarcodeToBatch;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBarcode;
import lk.coopfed.knoweb.m2catalogue.api.RetireBarcode;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.LookupResult;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every guard of RegisterBarcode, RetireBarcode and LinkBarcodeToBatch (22A section 6) with
 * its failing case, what each audits and publishes, and the lookup of 22A section 7 through
 * the rows they wrote (including the gtin + lot case without a batch barcode).
 */
class BarcodeHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = UUID.fromString("0190e660-0000-7000-8000-000000000001");
    private static final UUID MPCS_A = UUID.fromString("0190e660-0000-7000-8000-000000000002");
    private static final UUID MPCS_B = UUID.fromString("0190e660-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e660-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e660-0000-7000-8000-000000000100");

    private static final String EAN = "4791234567891";
    private static final String OTHER_EAN = "4791234567907";

    @Autowired
    RegisterBarcodeHandler register;

    @Autowired
    RetireBarcodeHandler retire;

    @Autowired
    LinkBarcodeToBatchHandler link;

    @Autowired
    CatalogueQueries queries;

    private final UUID sharedSku = Ids.next();
    private final UUID localSkuOfA = Ids.next();
    private final UUID draftSkuOfA = Ids.next();
    private final UUID localSkuOfB = Ids.next();
    private final UUID batchOfShared = Ids.next();
    private final UUID batchOfLocalA = Ids.next();

    @BeforeEach
    void arrange() {
        JdbcTemplate admin = superuserJdbc();
        clean();

        for (String unit : new String[] {"EA", "CASE"}) {
            admin.update(
                    "insert into catalogue.uom (uom_code, name_en, is_weight) values (?, ?, false) on conflict do nothing",
                    unit,
                    unit);
        }
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M2BARC', 'M2 barcode tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);

        insertSku(admin, sharedSku, "BC-SHARED", FEDERATION, "SHARED", "Milk powder 400g", "කිරිපිටි", "பால் மா");
        insertSku(admin, localSkuOfA, "BC-LOCAL-A", MPCS_A, "LOCAL", "Red rice 1kg", null, null);
        insertSku(admin, draftSkuOfA, "BC-DRAFT-A", MPCS_A, "DRAFT", "Not yet", null, null);
        insertSku(admin, localSkuOfB, "BC-LOCAL-B", MPCS_B, "LOCAL", "Other rice", null, null);

        admin.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, expiry_date, printed_mrp, owner_entity_id)"
                        + " values (?, ?, 'B2411A', date '2027-03-31', 1080.00, ?)",
                batchOfShared,
                sharedSku,
                MPCS_A);
        admin.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, owner_entity_id) values (?, ?, 'R1', ?)",
                batchOfLocalA,
                localSkuOfA,
                MPCS_A);

        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.batch, catalogue.sku cascade");
        admin.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX_CATEGORY);
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    // ---- RegisterBarcode ---------------------------------------------------------------------

    @Test
    void theOwnerRegistersAFactoryCodeAuditsAndPublishes() {
        register.handle(new RegisterBarcode(sharedSku, EAN, "ean13", "ea", null), own(FEDERATION));

        assertThat(row(EAN, "EAN13", FEDERATION)).isPresent().get().satisfies(row -> {
            assertThat(row.get("sku_id")).isEqualTo(sharedSku);
            assertThat(row.get("uom_code")).isEqualTo("EA");
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            assertThat(row.get("batch_id")).isNull();
        });

        assertThat(audit("BARCODE_REGISTERED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("sku");
            assertThat(record.subject().id()).isEqualTo(sharedSku);
            assertThat(record.before()).isNull();
            assertThat(((Map<?, ?>) record.after()).get("barcode")).isEqualTo(EAN);
        });
        assertThat(events(BarcodeRegistered.class))
                .containsExactly(new BarcodeRegistered(sharedSku, FEDERATION, EAN, "EAN13", "EA", null));
    }

    @Test
    void aFactoryCodeIsTheSkuOwnersAndAnInternalCodeIsAnyEntitysOnAnItemItSells() {
        // A society cannot put a factory code on the Federation's SHARED item.
        refused(
                () -> register.handle(new RegisterBarcode(sharedSku, EAN, "EAN13", "EA", null), own(MPCS_A)),
                "m2.sku.owner_mismatch");
        // Nor on another society's LOCAL item, which it cannot see.
        refused(
                () -> register.handle(new RegisterBarcode(localSkuOfB, EAN, "EAN13", "EA", null), own(MPCS_A)),
                "m2.sku.not_found");
        assertThat(kernel.committedAudit()).isEmpty();

        // Its own INTERNAL sticker on the SHARED item is its own row.
        register.handle(new RegisterBarcode(sharedSku, "2000001", "INTERNAL", "EA", null), own(MPCS_A));
        assertThat(row("2000001", "INTERNAL", MPCS_A)).isPresent();

        // Another society uses the same INTERNAL code for another item: unique per owner (B-I2).
        register.handle(new RegisterBarcode(localSkuOfB, "2000001", "INTERNAL", "EA", null), own(MPCS_B));
        assertThat(row("2000001", "INTERNAL", MPCS_B)).isPresent();

        // But not twice within one entity.
        refused(
                () -> register.handle(new RegisterBarcode(localSkuOfA, "2000001", "INTERNAL", "EA", null), own(MPCS_A)),
                "m2.barcode.already_registered");
        // And not on an item it cannot see.
        refused(
                () -> register.handle(new RegisterBarcode(localSkuOfB, "2000002", "INTERNAL", "EA", null), own(MPCS_A)),
                "m2.sku.not_found");

        assertThat(audit("BARCODE_REGISTERED")).hasSize(2);
    }

    @Test
    void aFactoryCodeIsUniqueFederationWideEvenAcrossRowsRlsHides() {
        register.handle(new RegisterBarcode(localSkuOfA, EAN, "EAN13", "EA", null), own(MPCS_A));
        kernel.reset();

        // Society B cannot see A's LOCAL item or its barcode; the unique index still refuses.
        refused(
                () -> register.handle(new RegisterBarcode(localSkuOfB, EAN, "EAN13", "EA", null), own(MPCS_B)),
                "m2.barcode.already_registered");
        // The Federation sees the SHARED path only: same answer, from the read this time.
        register.handle(new RegisterBarcode(sharedSku, OTHER_EAN, "EAN13", "EA", null), own(FEDERATION));
        refused(
                () -> register.handle(
                        new RegisterBarcode(sharedSku, OTHER_EAN, "EAN13", "CASE", null), own(FEDERATION)),
                "m2.barcode.already_registered");

        assertThat(superuserJdbc().queryForObject("select count(*) from catalogue.sku_barcode", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void theRegisterGuardsRefuseBeforeAnythingIsWritten() {
        refused(
                () -> register.handle(new RegisterBarcode(draftSkuOfA, EAN, "EAN13", "EA", null), own(MPCS_A)),
                "m2.barcode.sku_not_active");
        refused(
                () -> register.handle(new RegisterBarcode(localSkuOfA, EAN, "CODE128", "EA", null), own(MPCS_A)),
                "m2.barcode.symbology_invalid");
        refused(
                () -> register.handle(
                        new RegisterBarcode(localSkuOfA, "479123456789", "EAN13", "EA", null), own(MPCS_A)),
                "m2.barcode.format_invalid");
        refused(
                () -> register.handle(
                        new RegisterBarcode(localSkuOfA, "4791234567890", "EAN13", "EA", null), own(MPCS_A)),
                "m2.barcode.check_digit_invalid");
        refused(
                () -> register.handle(new RegisterBarcode(localSkuOfA, "96385075", "EAN8", "EA", null), own(MPCS_A)),
                "m2.barcode.check_digit_invalid");
        refused(
                () -> register.handle(
                        new RegisterBarcode(localSkuOfA, "036000291453", "UPCA", "EA", null), own(MPCS_A)),
                "m2.barcode.check_digit_invalid");
        refused(
                () -> register.handle(new RegisterBarcode(localSkuOfA, EAN, "EAN13", "PALLET", null), own(MPCS_A)),
                "m2.barcode.uom_unknown");
        refused(
                () -> register.handle(new RegisterBarcode(localSkuOfA, EAN, "EAN13", "EA", batchOfShared), own(MPCS_A)),
                "m2.barcode.batch_mismatch");
        refused(
                () -> register.handle(
                        new RegisterBarcode(localSkuOfA, EAN, "EAN13", "EA", null),
                        ScopeContext.dev(USER, MPCS_A, UUID.randomUUID())),
                "scope.invalid");

        assertThat(superuserJdbc().queryForObject("select count(*) from catalogue.sku_barcode", Integer.class))
                .isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // A batch of the SKU is accepted, and a GS1 element string needs no check of its own.
        register.handle(new RegisterBarcode(localSkuOfA, EAN, "EAN13", "EA", batchOfLocalA), own(MPCS_A));
        register.handle(
                new RegisterBarcode(localSkuOfA, "0104791234567907" + "10R1", "GS1_128", "EA", batchOfLocalA),
                own(MPCS_A));
        assertThat(audit("BARCODE_REGISTERED")).hasSize(2);
    }

    // ---- RetireBarcode -----------------------------------------------------------------------

    @Test
    void theOwnerRetiresItsRowWithAReasonAndTheCodeIsFreeAgain() {
        register.handle(new RegisterBarcode(sharedSku, EAN, "EAN13", "EA", null), own(FEDERATION));
        kernel.reset();

        retire.handle(
                new RetireBarcode(sharedSku, EAN, "EAN13", "WRONG_ITEM", "Printed on the wrong pack"), own(FEDERATION));

        assertThat(row(EAN, "EAN13", FEDERATION)).isPresent().get().satisfies(row -> assertThat(row.get("status"))
                .isEqualTo("RETIRED"));
        assertThat(audit("BARCODE_RETIRED")).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(sharedSku);
            assertThat(((Map<?, ?>) record.before()).get("status")).isEqualTo("ACTIVE");
            assertThat(((Map<?, ?>) record.after()).get("status")).isEqualTo("RETIRED");
            assertThat(record.reason()).isEqualTo("WRONG_ITEM: Printed on the wrong pack");
        });
        assertThat(events(BarcodeRetired.class))
                .containsExactly(new BarcodeRetired(sharedSku, FEDERATION, EAN, "EAN13", "EA", null));

        kernel.reset();

        // Retired twice is a problem; the code may be registered again on another item.
        refused(
                () -> retire.handle(new RetireBarcode(sharedSku, EAN, "EAN13", "AGAIN", null), own(FEDERATION)),
                "m2.barcode.not_active");
        register.handle(new RegisterBarcode(localSkuOfA, EAN, "EAN13", "EA", null), own(MPCS_A));
        assertThat(audit("BARCODE_REGISTERED")).hasSize(1);
    }

    @Test
    void onlyTheRowsOwnerRetiresItAndAReasonIsRequired() {
        register.handle(new RegisterBarcode(sharedSku, EAN, "EAN13", "EA", null), own(FEDERATION));
        register.handle(new RegisterBarcode(sharedSku, "2000001", "INTERNAL", "EA", null), own(MPCS_A));
        kernel.reset();

        // A society sees the Federation's factory row (shared_read) but it is not its own.
        refused(
                () -> retire.handle(new RetireBarcode(sharedSku, EAN, "EAN13", "X", null), own(MPCS_A)),
                "m2.barcode.not_found");
        // Another society cannot retire A's INTERNAL sticker.
        refused(
                () -> retire.handle(new RetireBarcode(sharedSku, "2000001", "INTERNAL", "X", null), own(MPCS_B)),
                "m2.barcode.not_found");
        // The code must belong to the SKU named.
        refused(
                () -> retire.handle(new RetireBarcode(localSkuOfA, "2000001", "INTERNAL", "X", null), own(MPCS_A)),
                "m2.barcode.not_found");
        refused(
                () -> retire.handle(new RetireBarcode(sharedSku, "2000001", "INTERNAL", null, " "), own(MPCS_A)),
                "m2.barcode.reason_required");

        assertThat(row(EAN, "EAN13", FEDERATION).get().get("status")).isEqualTo("ACTIVE");
        assertThat(row("2000001", "INTERNAL", MPCS_A).get().get("status")).isEqualTo("ACTIVE");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        retire.handle(new RetireBarcode(sharedSku, "2000001", "INTERNAL", null, "Sticker withdrawn"), own(MPCS_A));
        assertThat(row("2000001", "INTERNAL", MPCS_A).get().get("status")).isEqualTo("RETIRED");
    }

    // ---- LinkBarcodeToBatch ------------------------------------------------------------------

    @Test
    void theOwnerLinksItsRowToABatchOfTheSku() {
        register.handle(new RegisterBarcode(sharedSku, EAN, "EAN13", "EA", null), own(FEDERATION));
        kernel.reset();

        link.handle(new LinkBarcodeToBatch(sharedSku, EAN, "EAN13", batchOfShared), own(FEDERATION));

        assertThat(row(EAN, "EAN13", FEDERATION).get().get("batch_id")).isEqualTo(batchOfShared);
        assertThat(audit("BARCODE_LINKED")).singleElement().satisfies(record -> {
            assertThat(((Map<?, ?>) record.before()).get("batchId")).isNull();
            assertThat(((Map<?, ?>) record.after()).get("batchId")).isEqualTo(batchOfShared);
        });
        assertThat(events(BarcodeLinked.class))
                .containsExactly(new BarcodeLinked(sharedSku, FEDERATION, EAN, "EAN13", "EA", batchOfShared));
    }

    @Test
    void theLinkGuardsRefuseBeforeAnythingIsWritten() {
        register.handle(new RegisterBarcode(sharedSku, EAN, "EAN13", "EA", null), own(FEDERATION));
        register.handle(new RegisterBarcode(localSkuOfA, OTHER_EAN, "EAN13", "EA", null), own(MPCS_A));
        retire.handle(new RetireBarcode(localSkuOfA, OTHER_EAN, "EAN13", "X", null), own(MPCS_A));
        kernel.reset();

        refused(
                () -> link.handle(new LinkBarcodeToBatch(sharedSku, EAN, "EAN13", batchOfShared), own(MPCS_A)),
                "m2.barcode.not_found");
        refused(
                () -> link.handle(new LinkBarcodeToBatch(sharedSku, EAN, "EAN13", batchOfLocalA), own(FEDERATION)),
                "m2.barcode.batch_mismatch");
        refused(
                () -> link.handle(new LinkBarcodeToBatch(sharedSku, EAN, "EAN13", null), own(FEDERATION)),
                "request.field.required");
        refused(
                () -> link.handle(new LinkBarcodeToBatch(localSkuOfA, OTHER_EAN, "EAN13", batchOfLocalA), own(MPCS_A)),
                "m2.barcode.not_active");

        assertThat(row(EAN, "EAN13", FEDERATION).get().get("batch_id")).isNull();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- LookupByBarcode (22A section 7) -------------------------------------------------------

    @Test
    void anExactRowResolvesTheSkuTheUnitAndTheLinkedBatch() {
        register.handle(new RegisterBarcode(sharedSku, EAN, "EAN13", "EA", batchOfShared), own(FEDERATION));

        Optional<LookupResult> found = queries.lookupByBarcode(lookup(EAN, null, null, null, "EAN13"), own(MPCS_A));

        assertThat(found).isPresent().get().satisfies(result -> {
            assertThat(result.skuId()).isEqualTo(sharedSku);
            assertThat(result.skuCode()).isEqualTo("BC-SHARED");
            assertThat(result.uomCode()).isEqualTo("EA");
            assertThat(result.factorToBase()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.nameSi()).isEqualTo("කිරිපිටි");
            assertThat(result.fallbackSi()).isFalse();
            assertThat(result.nameTa()).isEqualTo("பால் மா");
            assertThat(result.fallbackTa()).isFalse();
            assertThat(result.batch().batchId()).isEqualTo(batchOfShared);
            assertThat(result.batch().batchNo()).isEqualTo("B2411A");
            assertThat(result.batch().expiryDate()).isEqualTo(LocalDate.of(2027, 3, 31));
            assertThat(result.batch().printedMrp()).isEqualByComparingTo("1080.00");
            assertThat(result.sellThrough()).isFalse();
        });

        // Without the symbology the same row is found; a code nobody registered is not.
        assertThat(queries.lookupByBarcode(lookup(EAN, null, null, null, null), own(MPCS_B)))
                .isPresent();
        assertThat(queries.lookupByBarcode(lookup(OTHER_EAN, null, null, null, null), own(MPCS_B)))
                .isEmpty();
    }

    @Test
    void aCaseCodeCarriesTheFactorInForceOrNoneWhenNoConversionIsDefined() {
        register.handle(new RegisterBarcode(sharedSku, OTHER_EAN, "EAN13", "CASE", null), own(FEDERATION));

        assertThat(queries.lookupByBarcode(lookup(OTHER_EAN, null, null, null, null), own(MPCS_A)))
                .get()
                .satisfies(result -> {
                    assertThat(result.uomCode()).isEqualTo("CASE");
                    assertThat(result.factorToBase()).isNull();
                });

        superuserJdbc()
                .update(
                        "insert into catalogue.sku_uom_conversion"
                                + " (sku_id, uom_code, factor_to_base, effective_from, owner_entity_id)"
                                + " values (?, 'CASE', 24, date '2020-01-01', ?)",
                        sharedSku,
                        FEDERATION);

        assertThat(queries.lookupByBarcode(lookup(OTHER_EAN, null, null, null, null), own(MPCS_A)))
                .get()
                .satisfies(result -> assertThat(result.factorToBase()).isEqualByComparingTo("24"));
    }

    @Test
    void aGtinWithALotResolvesTheBatchByItsNumberWithoutABatchBarcode() {
        register.handle(new RegisterBarcode(sharedSku, EAN, "EAN13", "EA", null), own(FEDERATION));

        // The till parsed a 2D code: GTIN-14 (leading zero), lot and expiry.
        Optional<LookupResult> parsed = queries.lookupByBarcode(
                lookup(null, "0" + EAN, "B2411A", LocalDate.of(2027, 3, 31), "GS1_DATAMATRIX"), own(MPCS_A));

        assertThat(parsed).isPresent().get().satisfies(result -> {
            assertThat(result.skuId()).isEqualTo(sharedSku);
            assertThat(result.batch().batchId()).isEqualTo(batchOfShared);
        });

        // The same code sent whole as a GS1 element string is split here.
        Optional<LookupResult> whole = queries.lookupByBarcode(
                lookup("010" + EAN + "17270331" + "10B2411A", null, null, null, "GS1_DATAMATRIX"), own(MPCS_A));

        assertThat(whole).isPresent().get().satisfies(result -> assertThat(
                        result.batch().batchNo())
                .isEqualTo("B2411A"));

        // A lot nobody registered gives the item without a batch.
        assertThat(queries.lookupByBarcode(lookup(null, EAN, "NOPE", null, null), own(MPCS_A)))
                .get()
                .satisfies(result -> assertThat(result.batch()).isNull());
    }

    @Test
    void anInternalCodeResolvesInItsOwnersScopeOnly() {
        register.handle(new RegisterBarcode(sharedSku, "2000001", "INTERNAL", "EA", null), own(MPCS_A));
        register.handle(new RegisterBarcode(localSkuOfB, "2000001", "INTERNAL", "EA", null), own(MPCS_B));

        assertThat(queries.lookupByBarcode(lookup("2000001", null, null, null, null), own(MPCS_A)))
                .get()
                .satisfies(result -> assertThat(result.skuId()).isEqualTo(sharedSku));
        assertThat(queries.lookupByBarcode(lookup("2000001", null, null, null, null), own(MPCS_B)))
                .get()
                .satisfies(result -> {
                    assertThat(result.skuId()).isEqualTo(localSkuOfB);
                    assertThat(result.nameSi()).as("falls back to English").isEqualTo("Other rice");
                    assertThat(result.fallbackSi()).isTrue();
                    assertThat(result.fallbackTa()).isTrue();
                });
        // The Federation has no sticker 2000001: the societies' rows on its SHARED item do not count.
        assertThat(queries.lookupByBarcode(lookup("2000001", null, null, null, null), own(FEDERATION)))
                .isEmpty();
    }

    @Test
    void aRetiredCodeNoLongerResolves() {
        register.handle(new RegisterBarcode(sharedSku, EAN, "EAN13", "EA", null), own(FEDERATION));
        retire.handle(new RetireBarcode(sharedSku, EAN, "EAN13", "X", null), own(FEDERATION));

        assertThat(queries.lookupByBarcode(lookup(EAN, null, null, null, null), own(MPCS_A)))
                .isEmpty();
    }

    private static BarcodeLookup lookup(String barcode, String gtin, String lot, LocalDate expiry, String symbology) {
        return new BarcodeLookup(barcode, gtin, lot, expiry, symbology, null);
    }

    private static void insertSku(
            JdbcTemplate admin, UUID skuId, String code, UUID owner, String status, String en, String si, String ta) {
        admin.update(
                """
                insert into catalogue.sku
                    (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                     base_uom_code, tax_category_id)
                values (?, ?, ?, ?, ?, ?, ?, 'EA', ?)
                """,
                skuId,
                code,
                owner,
                status,
                en,
                si,
                ta,
                TAX_CATEGORY);
    }

    private static Optional<Map<String, Object>> row(String barcode, String symbology, UUID owner) {
        return superuserJdbc()
                .queryForList(
                        "select sku_id, uom_code, batch_id, status from catalogue.sku_barcode"
                                + " where barcode = ? and symbology = ? and owner_entity_id = ?",
                        barcode,
                        symbology,
                        owner)
                .stream()
                .findFirst();
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
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
