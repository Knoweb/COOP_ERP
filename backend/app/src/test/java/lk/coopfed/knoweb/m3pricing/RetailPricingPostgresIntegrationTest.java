package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceEntered;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceRescinded;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.DraftNewVersion;
import lk.coopfed.knoweb.m3pricing.api.EnterControlPrice;
import lk.coopfed.knoweb.m3pricing.api.MrpPolicyChanged;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.RescindControlPrice;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult;
import lk.coopfed.knoweb.m3pricing.api.SetMrpPolicy;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.EnterControlPriceHandler;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.RescindControlPriceHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.CreatePriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.DraftNewVersionHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.PublishPriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.SetLinesHandler;
import lk.coopfed.knoweb.m3pricing.internal.policy.SetMrpPolicyHandler;
import lk.coopfed.knoweb.m3pricing.query.ControlPriceView;
import lk.coopfed.knoweb.m3pricing.query.MrpPolicyView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.RetailPrice;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M3-06 and M3-07 (23A section 7; doc 23 flows 6.1, 6.4, 6.6): control prices (enter, supersede,
 * rescind, history), the MRP policy (set, effective lookup), RETAIL lists under the ceilings (the
 * control price and the lowest printed MRP in stock at the society's locations), ADVISORY lists,
 * and the retail price at a shop through the engine. Every guard with its failing case, and what
 * each handler audits and publishes.
 *
 * <p>Dates: a ceiling that must bind while drafting starts yesterday, a list applies from tomorrow
 * and prices are resolved for tomorrow, so a run that crosses midnight in Colombo reads the same
 * answers (the handlers take "today" from the clock when they run).
 */
class RetailPricingPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY = UUID.fromString("0190e750-0000-7000-8000-000000000005");
    private static final UUID DISTRIBUTOR = UUID.fromString("0190e750-0000-7000-8000-000000000003");
    private static final UUID SHOP = UUID.fromString("0190e750-0000-7000-8000-000000000105");
    private static final UUID USER = UUID.fromString("0190e750-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e750-0000-7000-8000-000000000100");

    @Autowired
    EnterControlPriceHandler enterControlPrice;

    @Autowired
    RescindControlPriceHandler rescindControlPrice;

    @Autowired
    SetMrpPolicyHandler setMrpPolicy;

    @Autowired
    CreatePriceListHandler create;

    @Autowired
    DraftNewVersionHandler draftNewVersion;

    @Autowired
    SetLinesHandler setLines;

    @Autowired
    PublishPriceListHandler publish;

    @Autowired
    PricingQueries queries;

    private final UUID rice = Ids.next();
    private final UUID sugar = Ids.next();
    private final UUID milk = Ids.next();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
    private final LocalDate yesterday = today.minusDays(1);
    private final LocalDate tomorrow = today.plusDays(1);

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3RETAIL', 'M3 retail tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, district,
                    financial_year_start_month, default_language, status)
                values (?, 'M3SOC', 'MPCS', 'M3 test society', 'Kurunegala', 1, 'si', 'ACTIVE')
                on conflict (entity_id) do nothing
                """,
                SOCIETY);
        admin.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en)"
                        + " values (?, ?, 'M3SHOP', 'SHOP', 'M3 test shop') on conflict (location_id) do nothing",
                SHOP,
                SOCIETY);
        insertSku(admin, rice, "M3R-RICE");
        insertSku(admin, sugar, "M3R-SUGAR");
        insertSku(admin, milk, "M3R-MILK");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.price_list_line, pricing.price_list cascade");
        admin.execute("truncate table pricing.control_price, pricing.mrp_policy");
        admin.update("delete from inventory.stock_lot where owner_entity_id = ?", SOCIETY);
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    // ---- control prices: flow 6.4 ------------------------------------------------------------------

    @Test
    void theFederationEntersAControlPriceAndALaterGazetteClosesIt() {
        UUID first = enterControlPrice.handle(
                new EnterControlPrice(rice, new BigDecimal("220.00"), "EA", yesterday, null, " 2492/29 "),
                own(FEDERATION));

        assertThat(queries.controlPriceFor(rice, "EA", tomorrow, own(SOCIETY)))
                .as("every scope reads the ceilings")
                .get()
                .satisfies(c -> {
                    assertThat(c.ceilingPrice()).isEqualByComparingTo("220.00");
                    assertThat(c.gazetteReference()).isEqualTo("2492/29");
                });
        assertThat(audit("CONTROL_PRICE_ENTERED"))
                .singleElement()
                .satisfies(r -> assertThat(r.subject().id()).isEqualTo(first));
        assertThat(events(ControlPriceEntered.class))
                .containsExactly(new ControlPriceEntered(
                        first, rice, new BigDecimal("220.00"), "EA", yesterday, null, "2492/29", null));
        kernel.reset();

        UUID second = enterControlPrice.handle(
                new EnterControlPrice(rice, new BigDecimal("210.00"), "EA", today.plusDays(10), null, "2493/01"),
                own(FEDERATION));

        List<ControlPriceView> history = queries.controlPrices(rice, own(SOCIETY));
        assertThat(history).extracting(ControlPriceView::controlPriceId).containsExactly(second, first);
        assertThat(history.get(1).effectiveTo()).isEqualTo(today.plusDays(9));
        assertThat(queries.controlPriceFor(rice, "EA", today.plusDays(9), own(SOCIETY)))
                .get()
                .satisfies(c -> assertThat(c.ceilingPrice()).isEqualByComparingTo("220.00"));
        assertThat(queries.controlPriceFor(rice, "EA", today.plusDays(10), own(SOCIETY)))
                .get()
                .satisfies(c -> assertThat(c.ceilingPrice()).isEqualByComparingTo("210.00"));
        assertThat(events(ControlPriceEntered.class))
                .singleElement()
                .satisfies(e -> assertThat(e.closedControlPriceId()).isEqualTo(first));
        assertThat(queries.controlPricesInForce(tomorrow, own(SOCIETY)))
                .extracting(ControlPriceView::controlPriceId)
                .containsExactly(first);
    }

    @Test
    void aControlPriceIsRescindedByGazetteAndTheReasonIsAudited() {
        UUID id = enterControlPrice.handle(
                new EnterControlPrice(rice, new BigDecimal("220"), "EA", yesterday, null, "2492/29"), own(FEDERATION));
        kernel.reset();

        refused(
                () -> rescindControlPrice.handle(
                        new RescindControlPrice(id, yesterday.minusDays(1), "Withdrawn", "2494/02"), own(FEDERATION)),
                "m3.control_price.last_day_invalid");
        refused(
                () -> rescindControlPrice.handle(
                        new RescindControlPrice(id, tomorrow, "Withdrawn", "2494/02"), own(SOCIETY)),
                "m3.control_price.federation_only");
        refused(
                () -> rescindControlPrice.handle(
                        new RescindControlPrice(id, tomorrow, " ", "2494/02"), own(FEDERATION)),
                "request.field.required");
        assertThat(kernel.committedAudit()).isEmpty();

        rescindControlPrice.handle(new RescindControlPrice(id, tomorrow, "Withdrawn", "2494/02"), own(FEDERATION));

        assertThat(queries.controlPriceFor(rice, "EA", tomorrow, own(SOCIETY))).isPresent();
        assertThat(queries.controlPriceFor(rice, "EA", tomorrow.plusDays(1), own(SOCIETY)))
                .isEmpty();
        assertThat(audit("CONTROL_PRICE_RESCINDED")).singleElement().satisfies(r -> {
            assertThat(((Map<?, ?>) r.after()).get("reason")).isEqualTo("Withdrawn");
            assertThat(((Map<?, ?>) r.after()).get("gazetteReference")).isEqualTo("2494/02");
        });
        assertThat(events(ControlPriceRescinded.class))
                .containsExactly(new ControlPriceRescinded(id, rice, tomorrow, "2494/02"));
    }

    @Test
    void enterControlPriceRefusesEachBrokenRuleAndCommitsNothing() {
        refused(
                () -> enterControlPrice.handle(
                        new EnterControlPrice(rice, new BigDecimal("220"), "EA", today, null, "2492/29"), own(SOCIETY)),
                "m3.control_price.federation_only");
        refused(
                () -> enterControlPrice.handle(
                        new EnterControlPrice(rice, new BigDecimal("220"), "EA", today, null, "2492/29"),
                        atShop(FEDERATION)),
                "scope.invalid");
        refused(
                () -> enterControlPrice.handle(
                        new EnterControlPrice(Ids.next(), new BigDecimal("220"), "EA", today, null, "2492/29"),
                        own(FEDERATION)),
                "m3.control_price.sku_not_active");
        refused(
                () -> enterControlPrice.handle(
                        new EnterControlPrice(rice, new BigDecimal("220"), "KG", today, null, "2492/29"),
                        own(FEDERATION)),
                "m3.control_price.uom_invalid");
        refused(
                () -> enterControlPrice.handle(
                        new EnterControlPrice(rice, new BigDecimal("220.005"), "EA", today, null, "2492/29"),
                        own(FEDERATION)),
                "m3.control_price.ceiling_invalid");
        refused(
                () -> enterControlPrice.handle(
                        new EnterControlPrice(rice, new BigDecimal("220"), "EA", today, today.minusDays(3), "2492/29"),
                        own(FEDERATION)),
                "m3.control_price.dates_invalid");
        refused(
                () -> enterControlPrice.handle(
                        new EnterControlPrice(rice, new BigDecimal("220"), "EA", today, null, ""), own(FEDERATION)),
                "request.field.required");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // A second entry that starts on or before an existing one of the same item overlaps it.
        enterControlPrice.handle(
                new EnterControlPrice(rice, new BigDecimal("220"), "EA", tomorrow, null, "2492/29"), own(FEDERATION));
        refused(
                () -> enterControlPrice.handle(
                        new EnterControlPrice(rice, new BigDecimal("215"), "EA", yesterday, null, "2492/40"),
                        own(FEDERATION)),
                "m3.control_price.overlap");
    }

    // ---- MRP policy (M3-07) --------------------------------------------------------------------------

    @Test
    void theEffectivePolicyIsTheSocietysThenTheFederationsThenTheDefault() {
        assertThat(queries.effectiveMrpPolicy(milk, own(SOCIETY))).satisfies(p -> {
            assertThat(p.policy()).isEqualTo("AUTO_LOWEST");
            assertThat(p.source()).isEqualTo(MrpPolicyView.DEFAULT);
        });

        UUID fed = setMrpPolicy.handle(new SetMrpPolicy(milk, "BARCODE_RESOLVED", null, null), own(FEDERATION));
        assertThat(queries.effectiveMrpPolicy(milk, own(SOCIETY))).satisfies(p -> {
            assertThat(p.policy()).isEqualTo("BARCODE_RESOLVED");
            assertThat(p.source()).isEqualTo(MrpPolicyView.FEDERATION);
            assertThat(p.policyId()).isEqualTo(fed);
        });
        kernel.reset();

        UUID societyRow = setMrpPolicy.handle(new SetMrpPolicy(milk, "PICKER", null, null), own(SOCIETY));
        assertThat(queries.effectiveMrpPolicy(milk, own(SOCIETY))).satisfies(p -> {
            assertThat(p.policy()).isEqualTo("PICKER");
            assertThat(p.source()).isEqualTo(MrpPolicyView.OWN);
            // No gap of its own: the configured Rs 20 or 5 % (doc 23 DR-4).
            assertThat(p.gapAmount()).isEqualByComparingTo("20");
            assertThat(p.gapPercent()).isEqualByComparingTo("5");
        });
        assertThat(audit("MRP_POLICY_SET")).singleElement().satisfies(r -> assertThat(r.before())
                .isNull());
        assertThat(events(MrpPolicyChanged.class))
                .containsExactly(new MrpPolicyChanged(societyRow, SOCIETY, milk, "PICKER", null, null));
        kernel.reset();

        // Setting it again replaces the row, and the audit keeps what it was.
        UUID again = setMrpPolicy.handle(new SetMrpPolicy(milk, "PICKER", new BigDecimal("30.00"), null), own(SOCIETY));
        assertThat(again).isEqualTo(societyRow);
        assertThat(queries.effectiveMrpPolicy(milk, own(SOCIETY)).gapAmount()).isEqualByComparingTo("30");
        assertThat(audit("MRP_POLICY_SET")).singleElement().satisfies(r -> assertThat(
                        ((Map<?, ?>) r.before()).get("policy"))
                .isEqualTo("PICKER"));
        assertThat(queries.listMrpPolicies(own(SOCIETY)))
                .extracting(MrpPolicyView::source)
                .containsExactly(MrpPolicyView.OWN);
        assertThat(queries.effectiveMrpPolicy(milk, own(DISTRIBUTOR)).source())
                .as("another entity still falls back to the Federation's row")
                .isEqualTo(MrpPolicyView.FEDERATION);
    }

    @Test
    void setMrpPolicyRefusesEachBrokenRule() {
        refused(
                () -> setMrpPolicy.handle(new SetMrpPolicy(milk, "LOWEST", null, null), own(SOCIETY)),
                "m3.mrp_policy.policy_invalid");
        refused(
                () -> setMrpPolicy.handle(
                        new SetMrpPolicy(milk, "AUTO_LOWEST", new BigDecimal("20"), null), own(SOCIETY)),
                "m3.mrp_policy.gap_not_allowed");
        refused(
                () -> setMrpPolicy.handle(new SetMrpPolicy(milk, "PICKER", null, new BigDecimal("101")), own(SOCIETY)),
                "m3.mrp_policy.gap_invalid");
        refused(
                () -> setMrpPolicy.handle(new SetMrpPolicy(Ids.next(), "PICKER", null, null), own(SOCIETY)),
                "m3.mrp_policy.sku_not_active");
        refused(
                () -> setMrpPolicy.handle(new SetMrpPolicy(milk, "PICKER", null, null), atShop(SOCIETY)),
                "scope.invalid");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- RETAIL lists: flow 6.1 -------------------------------------------------------------------

    @Test
    void aShelfListIsRefusedAboveTheCeilingsAndPublishedUnderThem() {
        enterControlPrice.handle(
                new EnterControlPrice(rice, new BigDecimal("220.00"), "EA", yesterday, null, "2492/29"),
                own(FEDERATION));
        UUID sugarBatch = batch(sugar, "S-1", "300.00");
        lot(sugarBatch, sugar, "12");
        kernel.reset();

        UUID list = create.handle(new CreatePriceList("RETAIL", "Society shelf prices"), own(SOCIETY));
        SetLinesResult refused = setLines.handle(
                new SetLines(list, List.of(line(rice, "240.00"), line(sugar, "310.00"), line(milk, "1050.00"))),
                own(SOCIETY));

        assertThat(refused.saved()).isFalse();
        assertThat(refused.outcomes().get(0)).satisfies(o -> {
            assertThat(o.reason()).isEqualTo("m3.price_list.line.above_control_price");
            assertThat(o.ceilingKind()).isEqualTo("CONTROL_PRICE");
            assertThat(o.ceilingValue()).isEqualByComparingTo("220.00");
            assertThat(o.ceilingRef()).isEqualTo("2492/29");
        });
        assertThat(refused.outcomes().get(1)).satisfies(o -> {
            assertThat(o.reason()).isEqualTo("m3.price_list.line.above_shelf_mrp");
            assertThat(o.ceilingKind()).isEqualTo("MRP");
            assertThat(o.ceilingValue()).isEqualByComparingTo("300.00");
            assertThat(o.ceilingRef()).isEqualTo("S-1");
        });
        assertThat(refused.outcomes().get(2).ok()).as("no ceiling binds milk").isTrue();
        assertThat(audit("PRICELIST_LINES_SET")).isEmpty();

        SetLinesResult saved = setLines.handle(
                new SetLines(list, List.of(line(rice, "220.00"), line(sugar, "295.00"), line(milk, "1050.00"))),
                own(SOCIETY));
        assertThat(saved.saved()).isTrue();
        assertThat(saved.outcomes().get(0).ceilingKind())
                .as("the binding ceiling is shown on an accepted line too")
                .isEqualTo("CONTROL_PRICE");
        publish.handle(new PublishPriceList(list, tomorrow), own(SOCIETY));
        assertThat(queries.getPriceList(list, own(SOCIETY))).get().satisfies(v -> assertThat(v.status())
                .isEqualTo("PUBLISHED"));

        // One list per society: its next prices are a new version, which carries the lines forward.
        refused(
                () -> create.handle(new CreatePriceList("RETAIL", "Second"), own(SOCIETY)),
                "m3.price_list.retail_exists");
        UUID v2 = draftNewVersion.handle(new DraftNewVersion(list), own(SOCIETY));
        assertThat(queries.lines(v2, own(SOCIETY))).hasSize(3);
    }

    @Test
    void onlyASocietyKeepsAShelfListAndOnlyTheFederationAnAdvisoryOne() {
        refused(
                () -> create.handle(new CreatePriceList("RETAIL", "Shelf"), own(DISTRIBUTOR)),
                "m3.price_list.retail_mpcs_only");
        refused(
                () -> create.handle(new CreatePriceList("ADVISORY", "Advice"), own(SOCIETY)),
                "m3.price_list.advisory_federation_only");
        assertThat(kernel.committedAudit()).isEmpty();

        UUID advisory = create.handle(new CreatePriceList("ADVISORY", "Federation advice"), own(FEDERATION));
        SetLinesResult tiered = setLines.handle(
                new SetLines(advisory, List.of(new SetLines.Line(rice, "EA", BigDecimal.TEN, new BigDecimal("200")))),
                own(FEDERATION));
        assertThat(tiered.outcomes().get(0).reason()).isEqualTo("m3.price_list.line.tier_not_allowed");
        setLines.handle(new SetLines(advisory, List.of(line(rice, "205.00"))), own(FEDERATION));
        publish.handle(new PublishPriceList(advisory, tomorrow), own(FEDERATION));

        assertThat(queries.advisoryLines(tomorrow, own(SOCIETY)))
                .as("a society reads the Federation's published advice")
                .singleElement()
                .satisfies(l -> assertThat(l.price()).isEqualByComparingTo("205.00"));
        assertThat(queries.advisoryLines(today.minusDays(5), own(SOCIETY))).isEmpty();
    }

    // ---- the retail price at a shop: flow 6.6 --------------------------------------------------------

    @Test
    void theShopPriceIsTheLowestOfListMrpAndControlPrice() {
        UUID riceLow = batch(rice, "R-980", "980.00");
        UUID riceHigh = batch(rice, "R-1040", "1040.00");
        lot(riceLow, rice, "3");
        lot(riceHigh, rice, "5");
        UUID list = create.handle(new CreatePriceList("RETAIL", "Society shelf prices"), own(SOCIETY));
        setLines.handle(new SetLines(list, List.of(line(rice, "980.00"), line(sugar, "300.00"))), own(SOCIETY));
        publish.handle(new PublishPriceList(list, tomorrow), own(SOCIETY));

        // doc 13 scenario 4: AUTO_LOWEST picks the lowest MRP on the shelf.
        assertThat(price(rice)).satisfies(p -> {
            assertThat(p.sellable()).isTrue();
            assertThat(p.listPrice()).isEqualByComparingTo("980.00");
            assertThat(p.mrpApplied()).isEqualByComparingTo("980.00");
            assertThat(p.unitPrice()).isEqualByComparingTo("980.00");
            assertThat(p.policy()).isEqualTo("AUTO_LOWEST");
            assertThat(p.batchId()).isEqualTo(riceLow);
        });

        // A gazette below the published list: the engine caps the price (the list is reviewed, M3-08).
        enterControlPrice.handle(
                new EnterControlPrice(rice, new BigDecimal("950.00"), "EA", tomorrow, null, "2492/29"),
                own(FEDERATION));
        assertThat(price(rice)).satisfies(p -> {
            assertThat(p.unitPrice()).isEqualByComparingTo("950.00");
            assertThat(p.controlPrice()).isEqualByComparingTo("950.00");
            assertThat(p.capReason()).isEqualTo("CONTROL_PRICE");
        });

        // PICKER with a gap of Rs 20: the MRPs differ by Rs 60, so the till must ask.
        setMrpPolicy.handle(new SetMrpPolicy(rice, "PICKER", new BigDecimal("20"), null), own(SOCIETY));
        assertThat(price(rice)).satisfies(p -> {
            assertThat(p.sellable()).isFalse();
            assertThat(p.reason()).isEqualTo("price.needs_pick");
        });

        assertThat(price(milk)).satisfies(p -> {
            assertThat(p.sellable()).isFalse();
            assertThat(p.reason()).isEqualTo("price.no_list_line");
        });
        assertThat(queries.resolveRetailPrice(SHOP, rice, "EA", BigDecimal.ONE, today, own(SOCIETY)))
                .as("the list applies from tomorrow")
                .get()
                .satisfies(p -> assertThat(p.sellable()).isFalse());
        assertThat(queries.resolveRetailPrice(SHOP, rice, "EA", BigDecimal.ONE, tomorrow, own(DISTRIBUTOR)))
                .as("another entity does not see the society's shop")
                .isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private RetailPrice price(UUID sku) {
        return queries.resolveRetailPrice(SHOP, sku, "EA", BigDecimal.ONE, tomorrow, atShop(SOCIETY))
                .orElseThrow();
    }

    private static SetLines.Line line(UUID sku, String price) {
        return new SetLines.Line(sku, "EA", BigDecimal.ZERO, new BigDecimal(price));
    }

    private static UUID batch(UUID sku, String batchNo, String mrp) {
        UUID id = Ids.next();
        superuserJdbc()
                .update(
                        "insert into catalogue.batch (batch_id, sku_id, batch_no, printed_mrp, owner_entity_id)"
                                + " values (?, ?, ?, ?, ?)",
                        id,
                        sku,
                        batchNo,
                        new BigDecimal(mrp),
                        FEDERATION);
        return id;
    }

    /** A GOOD lot at the society's shop, written as the superuser (M5's ledger is not under test). */
    private static void lot(UUID batch, UUID sku, String qty) {
        superuserJdbc()
                .update(
                        "insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id,"
                                + " qty_on_hand, unit_cost, received_at) values (?, ?, ?, ?, ?, ?, 90, now())",
                        Ids.next(),
                        SOCIETY,
                        SHOP,
                        batch,
                        sku,
                        new BigDecimal(qty));
    }

    private static void insertSku(JdbcTemplate admin, UUID skuId, String code) {
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, has_printed_mrp, tax_category_id)"
                        + " values (?, ?, ?, 'SHARED', ?, ?, ?, 'EA', false, true, ?)",
                skuId,
                code,
                FEDERATION,
                code,
                code,
                code,
                TAX_CATEGORY);
    }

    private static ScopeContext own(UUID entity) {
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(new Scope(entity, null)),
                new Scope(entity, null),
                PolicyClass.OWN,
                Set.of(),
                Instant.now(),
                Locale.ENGLISH,
                null);
    }

    private static ScopeContext atShop(UUID entity) {
        return ScopeContext.dev(USER, entity, entity.equals(SOCIETY) ? SHOP : Ids.next());
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
