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
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.DraftNewVersion;
import lk.coopfed.knoweb.m3pricing.api.PriceListDrafted;
import lk.coopfed.knoweb.m3pricing.api.PriceListLinesSet;
import lk.coopfed.knoweb.m3pricing.api.PriceListPublished;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult;
import lk.coopfed.knoweb.m3pricing.internal.list.CreatePriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.DraftNewVersionHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.PublishPriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.SetLinesHandler;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.TradePrice;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M3-04 (23A section 7; doc 23 flows 6.2): every guard of CreatePriceList, DraftNewVersion,
 * SetLines and PublishPriceList with its failing case, what each audits and publishes, the trade
 * price lookup M4 uses (tiers, versions by date, the buyer's view), and M3's answer to M1's
 * price-list check.
 */
class PriceListHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID DISTRIBUTOR = UUID.fromString("0190e730-0000-7000-8000-000000000003");
    private static final UUID STRANGER = UUID.fromString("0190e730-0000-7000-8000-000000000004");
    private static final UUID USER = UUID.fromString("0190e730-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e730-0000-7000-8000-000000000100");
    private static final UUID RELATIONSHIP = UUID.fromString("0190e730-0000-7000-8000-000000000401");

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

    @Autowired
    TradePriceListCheck check;

    private final UUID rice = Ids.next();
    private final UUID sugar = Ids.next();
    private final UUID draftSku = Ids.next();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3LIST', 'M3 list tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        insertSku(admin, rice, "M3-RICE", "SHARED");
        insertSku(admin, sugar, "M3-SUGAR", "SHARED");
        insertSku(admin, draftSku, "M3-DRAFT", "DRAFT");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.price_list_line, pricing.price_list cascade");
        admin.update("delete from party.entity_relationship where relationship_id = ?", RELATIONSHIP);
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    // ---- flow 6.2: draft, lines with tiers, publish, the buyer's order line priced by tier ------

    @Test
    void aTradeListIsDraftedPricedPublishedAndResolvedByTier() {
        UUID list = create.handle(new CreatePriceList("TRADE", " Federation uniform list "), own(FEDERATION));

        assertThat(queries.getPriceList(list, own(FEDERATION))).get().satisfies(view -> {
            assertThat(view.status()).isEqualTo("DRAFT");
            assertThat(view.version()).isEqualTo(1);
            assertThat(view.rootPriceListId()).isEqualTo(list);
            assertThat(view.name()).isEqualTo("Federation uniform list");
        });
        assertThat(audit("PRICELIST_DRAFTED"))
                .singleElement()
                .satisfies(r -> assertThat(r.subject().id()).isEqualTo(list));
        assertThat(events(PriceListDrafted.class))
                .containsExactly(new PriceListDrafted(list, list, FEDERATION, "TRADE", 1));
        kernel.reset();

        SetLinesResult result = setLines.handle(
                new SetLines(
                        list, List.of(line(rice, "0", "100.00"), line(rice, "10", "95.5000"), line(sugar, "0", "250"))),
                own(FEDERATION));
        assertThat(result.saved()).isTrue();
        assertThat(result.outcomes()).allSatisfy(o -> assertThat(o.ok()).isTrue());
        assertThat(audit("PRICELIST_LINES_SET")).hasSize(1);
        assertThat(events(PriceListLinesSet.class)).containsExactly(new PriceListLinesSet(list, FEDERATION, 3));
        kernel.reset();

        publish.handle(new PublishPriceList(list, today), own(FEDERATION));
        assertThat(queries.getPriceList(list, own(FEDERATION))).get().satisfies(view -> {
            assertThat(view.status()).isEqualTo("PUBLISHED");
            assertThat(view.applyFrom()).isEqualTo(today);
        });
        assertThat(queries.lines(list, own(FEDERATION)))
                .allSatisfy(l -> assertThat(l.effectiveFrom()).isEqualTo(today));
        assertThat(audit("PRICELIST_PUBLISHED")).singleElement().satisfies(r -> assertThat(
                        ((Map<?, ?>) r.after()).get("status"))
                .isEqualTo("PUBLISHED"));
        assertThat(events(PriceListPublished.class)).singleElement().satisfies(e -> {
            assertThat(e.priceListId()).isEqualTo(list);
            assertThat(e.applyFrom()).isEqualTo(today);
            assertThat(e.skuIds()).containsExactlyInAnyOrder(rice, sugar);
        });

        bind(list);
        // The buyer prices its order in its own scope (24A section 6), by the ordered quantity.
        assertThat(price(rice, "9.999", today)).isEqualByComparingTo("100.00");
        assertThat(price(rice, "10", today)).isEqualByComparingTo("95.50");
        assertThat(queries.resolveTradePrice(
                        FEDERATION, DISTRIBUTOR, rice, "EA", new BigDecimal("12"), today, own(DISTRIBUTOR)))
                .get()
                .satisfies(p -> {
                    assertThat(p.tierFromQty()).isEqualByComparingTo("10");
                    assertThat(p.relationshipId()).isEqualTo(RELATIONSHIP);
                });
        assertThat(queries.resolveTradePrice(
                        RELATIONSHIP, rice, "EA", BigDecimal.ONE, today.minusDays(1), own(DISTRIBUTOR)))
                .as("not yet in force the day before")
                .isEmpty();
        assertThat(queries.resolveTradePrice(RELATIONSHIP, rice, "EA", BigDecimal.ONE, today, own(STRANGER)))
                .as("a stranger sees neither the relationship nor the list")
                .isEmpty();
    }

    @Test
    void aNewVersionAnswersFromItsApplyFromAndTheOldOneBeforeIt() {
        UUID v1 = published(List.of(line(rice, "0", "100")), today);
        bind(v1);
        kernel.reset();

        UUID v2 = draftNewVersion.handle(new DraftNewVersion(v1), own(FEDERATION));
        assertThat(queries.getPriceList(v2, own(FEDERATION))).get().satisfies(view -> {
            assertThat(view.version()).isEqualTo(2);
            assertThat(view.rootPriceListId()).isEqualTo(v1);
            assertThat(view.sourceVersionId()).isEqualTo(v1);
        });
        assertThat(events(PriceListDrafted.class))
                .containsExactly(new PriceListDrafted(v2, v1, FEDERATION, "TRADE", 2));
        refused(() -> draftNewVersion.handle(new DraftNewVersion(v1), own(FEDERATION)), "m3.price_list.draft_exists");

        setLines.handle(new SetLines(v2, List.of(line(rice, "0", "110"))), own(FEDERATION));
        publish.handle(new PublishPriceList(v2, today.plusDays(10)), own(FEDERATION));

        assertThat(queries.getPriceList(v1, own(FEDERATION))).get().satisfies(v -> assertThat(v.status())
                .isEqualTo("SUPERSEDED"));
        assertThat(price(rice, "1", today)).isEqualByComparingTo("100");
        assertThat(price(rice, "1", today.plusDays(9))).isEqualByComparingTo("100");
        assertThat(price(rice, "1", today.plusDays(10))).isEqualByComparingTo("110");
        // The buyer reads both published versions of the bound list, never another list.
        assertThat(queries.listPriceLists(null, null, own(DISTRIBUTOR)))
                .extracting(v -> v.priceListId())
                .containsExactlyInAnyOrder(v1, v2);
    }

    // ---- guards -----------------------------------------------------------------------------------

    @Test
    void createRefusesAKindNotBuiltYetAndAScopeThatIsNotTheOwnersEntityWide() {
        refused(
                () -> create.handle(new CreatePriceList("RETAIL", "Shelf"), own(DISTRIBUTOR)),
                "m3.price_list.retail_mpcs_only");
        refused(() -> create.handle(new CreatePriceList("TRADE", "At a shop"), atShop(DISTRIBUTOR)), "scope.invalid");
        refused(() -> create.handle(new CreatePriceList("TRADE", "Viewer"), viewer(DISTRIBUTOR)), "scope.invalid");
        refused(() -> create.handle(new CreatePriceList("TRADE", "  "), own(DISTRIBUTOR)), "request.field.required");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void setLinesRefusesEachBadLineAndStoresNothing() {
        UUID list = create.handle(new CreatePriceList("TRADE", "Checked"), own(FEDERATION));
        kernel.reset();

        SetLinesResult result = setLines.handle(
                new SetLines(
                        list,
                        List.of(
                                line(rice, "5", "100"), // tiers must start at 0
                                line(sugar, "0", "10"),
                                line(sugar, "0", "11"), // the same tier twice
                                new SetLines.Line(draftSku, "EA", BigDecimal.ZERO, BigDecimal.ONE), // not active
                                new SetLines.Line(Ids.next(), "EA", BigDecimal.ZERO, BigDecimal.ONE), // unknown
                                new SetLines.Line(rice, "KG", BigDecimal.ZERO, BigDecimal.ONE), // not the base unit
                                new SetLines.Line(rice, "EA", BigDecimal.ONE, new BigDecimal("-1")),
                                new SetLines.Line(rice, "EA", new BigDecimal("2"), new BigDecimal("1.00001")))),
                own(FEDERATION));

        assertThat(result.saved()).isFalse();
        assertThat(result.outcomes())
                .extracting(SetLinesResult.Outcome::reason)
                .containsExactly(
                        "m3.price_list.line.tier_base_missing",
                        "m3.price_list.line.duplicate",
                        "m3.price_list.line.duplicate",
                        "m3.price_list.line.sku_not_active",
                        "m3.price_list.line.sku_not_active",
                        "m3.price_list.line.uom_invalid",
                        "m3.price_list.line.price_negative",
                        "m3.price_list.line.price_precision");
        assertThat(queries.lines(list, own(FEDERATION))).isEmpty();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void descendingTiersAreRefused() {
        UUID list = create.handle(new CreatePriceList("TRADE", "Descending"), own(FEDERATION));
        SetLinesResult result = setLines.handle(
                new SetLines(list, List.of(line(rice, "0", "100"), line(rice, "20", "90"), line(rice, "10", "95"))),
                own(FEDERATION));
        assertThat(result.saved()).isFalse();
        assertThat(result.outcomes())
                .extracting(SetLinesResult.Outcome::reason)
                .containsOnly("m3.price_list.line.tiers_not_ascending");
    }

    @Test
    void aTradePriceAboveTheLowestPrintedMrpIsStoredWithAReview() {
        superuserJdbc()
                .update(
                        "insert into catalogue.batch (batch_id, sku_id, batch_no, printed_mrp, owner_entity_id)"
                                + " values (?, ?, 'M3-B1', 90.00, ?)",
                        Ids.next(),
                        rice,
                        FEDERATION);
        UUID list = create.handle(new CreatePriceList("TRADE", "Above MRP"), own(FEDERATION));

        SetLinesResult result = setLines.handle(
                new SetLines(list, List.of(line(rice, "0", "95"), line(sugar, "0", "10"))), own(FEDERATION));

        assertThat(result.saved()).isTrue();
        assertThat(result.outcomes())
                .extracting(SetLinesResult.Outcome::review)
                .containsExactly("m3.price_list.review.above_mrp", null);
    }

    @Test
    void onlyTheOwnersDraftTakesLinesAndOnlyADraftIsPublished() {
        UUID list = published(List.of(line(rice, "0", "100")), today);
        kernel.reset();

        refused(
                () -> setLines.handle(new SetLines(list, List.of(line(rice, "0", "1"))), own(FEDERATION)),
                "m3.price_list.not_draft");
        refused(() -> publish.handle(new PublishPriceList(list, today), own(FEDERATION)), "m3.price_list.not_draft");
        refused(
                () -> setLines.handle(new SetLines(list, List.of(line(rice, "0", "1"))), own(DISTRIBUTOR)),
                "m3.price_list.not_found");
        refused(() -> draftNewVersion.handle(new DraftNewVersion(list), own(STRANGER)), "m3.price_list.not_found");

        UUID draft = create.handle(new CreatePriceList("TRADE", "Draft"), own(FEDERATION));
        kernel.reset();
        refused(
                () -> draftNewVersion.handle(new DraftNewVersion(draft), own(FEDERATION)),
                "m3.price_list.not_published");
        refused(() -> publish.handle(new PublishPriceList(draft, today), own(FEDERATION)), "m3.price_list.no_lines");
        setLines.handle(new SetLines(draft, List.of(line(rice, "0", "100"))), own(FEDERATION));
        kernel.reset();
        refused(
                () -> publish.handle(new PublishPriceList(draft, today.minusDays(1)), own(FEDERATION)),
                "m3.price_list.apply_from_past");

        // A SKU deactivated after its line was set: publication runs the validator again.
        superuserJdbc()
                .update("update catalogue.sku set status = 'INACTIVE', prior_status = 'SHARED' where sku_id = ?", rice);
        refused(
                () -> publish.handle(new PublishPriceList(draft, today), own(FEDERATION)),
                "m3.price_list.lines_invalid");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aPublishedLineCannotBeChangedEvenBehindTheHandlers() {
        UUID list = published(List.of(line(rice, "0", "100")), today);
        assertThatThrownBy(() ->
                        superuserJdbc().update("delete from pricing.price_list_line where price_list_id = ?", list))
                .hasMessageContaining("is not a draft");
    }

    // ---- M1's question: may this list be bound to a relationship of this seller? ----------------

    @Test
    void theTradePriceListCheckAcceptsOnlyAPublishedTradeListOfTheSeller() {
        UUID list = published(List.of(line(rice, "0", "100")), today);
        UUID draft = create.handle(new CreatePriceList("TRADE", "Not yet"), own(FEDERATION));

        assertThat(check.refusal(list, FEDERATION, own(FEDERATION))).isEmpty();
        assertThat(check.refusal(draft, FEDERATION, own(FEDERATION))).contains("m3.price_list.not_published");
        assertThat(check.refusal(Ids.next(), FEDERATION, own(FEDERATION))).contains("m3.price_list.not_found");
        assertThat(check.refusal(null, FEDERATION, own(FEDERATION))).contains("m3.price_list.not_found");
        // Another seller's list is not visible in this seller's scope.
        assertThat(check.refusal(list, DISTRIBUTOR, own(DISTRIBUTOR))).contains("m3.price_list.not_found");
        assertThat(check.refusal(list, DISTRIBUTOR, own(FEDERATION))).contains("m3.price_list.not_the_sellers");
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private UUID published(List<SetLines.Line> lines, LocalDate applyFrom) {
        UUID list = create.handle(new CreatePriceList("TRADE", "Published"), own(FEDERATION));
        setLines.handle(new SetLines(list, lines), own(FEDERATION));
        publish.handle(new PublishPriceList(list, applyFrom), own(FEDERATION));
        return list;
    }

    private BigDecimal price(UUID sku, String qty, LocalDate date) {
        return queries.resolveTradePrice(RELATIONSHIP, sku, "EA", new BigDecimal(qty), date, own(DISTRIBUTOR))
                .map(TradePrice::unitPrice)
                .orElse(null);
    }

    private static void bind(UUID list) {
        superuserJdbc()
                .update(
                        "insert into party.entity_relationship (relationship_id, seller_entity_id, buyer_entity_id,"
                                + " price_list_id, status, effective_from) values (?, ?, ?, ?, 'ACTIVE', DATE '2026-01-01')",
                        RELATIONSHIP,
                        FEDERATION,
                        DISTRIBUTOR,
                        list);
    }

    private static SetLines.Line line(UUID sku, String tier, String price) {
        return new SetLines.Line(sku, "EA", new BigDecimal(tier), new BigDecimal(price));
    }

    private static void insertSku(JdbcTemplate admin, UUID skuId, String code, String status) {
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, tax_category_id)"
                        + " values (?, ?, ?, ?, ?, ?, ?, 'EA', false, ?)",
                skuId,
                code,
                FEDERATION,
                status,
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
        return ScopeContext.dev(USER, entity, Ids.next());
    }

    private static ScopeContext viewer(UUID entity) {
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
