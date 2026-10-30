package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import lk.coopfed.knoweb.m3pricing.api.PriceListWithdrawn;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult;
import lk.coopfed.knoweb.m3pricing.api.WithdrawDraft;
import lk.coopfed.knoweb.m3pricing.internal.list.CreatePriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.DraftNewVersionHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.PublishPriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.SetLinesHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.WithdrawDraftHandler;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.TradePrice;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M3-04 (23A section 7; doc 23 flows 6.2): every guard of CreatePriceList, DraftNewVersion,
 * SetLines and PublishPriceList with its failing case, what each audits and publishes, the trade
 * price lookup M4 uses (tiers, versions by date, the buyer's view), and M3's answer to M1's
 * price-list check.
 */
@Import(PinnedClock.class)
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
    WithdrawDraftHandler withdrawDraft;

    @Autowired
    PricingQueries queries;

    @Autowired
    TradePriceListCheck check;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    java.time.Clock clock;

    private final UUID rice = Ids.next();
    private final UUID sugar = Ids.next();
    private final UUID draftSku = Ids.next();
    private final LocalDate today = PinnedClock.TODAY;

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
        assertThat(queries.lines(v1, own(FEDERATION)))
                .allSatisfy(line -> assertThat(line.effectiveTo()).isEqualTo(today.plusDays(9)));
        assertThat(queries.lines(v2, own(FEDERATION)))
                .allSatisfy(line -> assertThat(line.effectiveFrom()).isEqualTo(today.plusDays(10)));

        // The old version's price cannot be edited after closure.
        assertThatThrownBy(() -> superuserJdbc()
                        .update("update pricing.price_list_line set price = 1" + " where price_list_id = ?", v1))
                .hasMessageContaining("is not a draft");
        // The buyer reads both published versions of the bound list, never another list.
        assertThat(queries.listPriceLists(null, null, own(DISTRIBUTOR)))
                .extracting(v -> v.priceListId())
                .containsExactlyInAnyOrder(v1, v2);
    }

    @Test
    void withdrawingDraftRecordsAuditAndEventAndAllowsAnotherVersion() {
        UUID published = published(List.of(line(rice, "0", "100")), today);
        UUID draft = draftNewVersion.handle(new DraftNewVersion(published), own(FEDERATION));
        kernel.reset();

        UUID withdrawn = withdrawDraft.handle(new WithdrawDraft(draft, "  Pricing revised  "), own(FEDERATION));

        assertThat(withdrawn).isEqualTo(draft);
        assertThat(queries.getPriceList(draft, own(FEDERATION))).get().satisfies(view -> assertThat(view.status())
                .isEqualTo("WITHDRAWN"));
        assertThat(queries.lines(draft, own(FEDERATION))).hasSize(1);

        assertThat(audit("PRICELIST_WITHDRAWN")).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(draft);
            assertThat(((Map<?, ?>) record.after()).get("reason")).isEqualTo("Pricing revised");
        });

        assertThat(events(PriceListWithdrawn.class))
                .containsExactly(new PriceListWithdrawn(draft, published, FEDERATION, "TRADE", 2));

        refused(
                () -> withdrawDraft.handle(new WithdrawDraft(draft, "Again"), own(FEDERATION)),
                "m3.price_list.not_draft");

        assertThat(audit("PRICELIST_WITHDRAWN")).hasSize(1);
        assertThat(events(PriceListWithdrawn.class)).hasSize(1);

        UUID replacement = draftNewVersion.handle(new DraftNewVersion(published), own(FEDERATION));

        assertThat(queries.getPriceList(replacement, own(FEDERATION))).get().satisfies(view -> {
            assertThat(view.status()).isEqualTo("DRAFT");
            assertThat(view.version()).isEqualTo(3);
            assertThat(view.rootPriceListId()).isEqualTo(published);
        });
    }

    @Test
    void withdrawalRefusesMissingReasonOtherOwnerAndPublishedVersion() {
        UUID draft = create.handle(new CreatePriceList("TRADE", "Temporary"), own(FEDERATION));
        UUID published = published(List.of(line(rice, "0", "100")), today);
        kernel.reset();

        refused(() -> withdrawDraft.handle(new WithdrawDraft(draft, " "), own(FEDERATION)), "request.field.required");
        refused(() -> withdrawDraft.handle(new WithdrawDraft(draft, null), own(FEDERATION)), "request.field.required");
        refused(
                () -> withdrawDraft.handle(new WithdrawDraft(draft, "Other owner"), own(DISTRIBUTOR)),
                "m3.price_list.not_found");
        refused(() -> withdrawDraft.handle(new WithdrawDraft(draft, "At shop"), atShop(FEDERATION)), "scope.invalid");
        refused(
                () -> withdrawDraft.handle(new WithdrawDraft(published, "Cannot withdraw"), own(FEDERATION)),
                "m3.price_list.not_draft");

        assertThat(queries.getPriceList(draft, own(FEDERATION))).get().satisfies(view -> assertThat(view.status())
                .isEqualTo("DRAFT"));
        assertThat(audit("PRICELIST_WITHDRAWN")).isEmpty();
        assertThat(events(PriceListWithdrawn.class)).isEmpty();
    }

    @Test
    void draftingFromSupersededVersionRecordsWarningAndCopiesSelectedSource() {
        UUID v1 = published(List.of(line(rice, "0", "100")), today);
        UUID v2 = draftNewVersion.handle(new DraftNewVersion(v1), own(FEDERATION));

        setLines.handle(new SetLines(v2, List.of(line(rice, "0", "120"))), own(FEDERATION));
        publish.handle(new PublishPriceList(v2, today.plusDays(1)), own(FEDERATION));
        kernel.reset();

        // The caller deliberately chooses v1, not the newer v2.
        UUID v3 = draftNewVersion.handle(new DraftNewVersion(v1), own(FEDERATION));

        assertThat(queries.getPriceList(v3, own(FEDERATION))).get().satisfies(view -> {
            assertThat(view.status()).isEqualTo("DRAFT");
            assertThat(view.sourceVersionId()).isEqualTo(v1);
            assertThat(view.version()).isEqualTo(3);
        });

        assertThat(queries.lines(v3, own(FEDERATION))).singleElement().satisfies(line -> assertThat(line.price())
                .isEqualByComparingTo("100"));

        assertThat(audit("PRICELIST_DRAFTED")).singleElement().satisfies(record -> assertThat(
                        ((Map<?, ?>) record.after()).get("sourceSuperseded"))
                .isEqualTo(true));
    }

    @Test
    void tradePriceListAcceptsConvertedCasePricesAndResolvesCaseTiers() {
        JdbcTemplate admin = superuserJdbc();
        admin.update("insert into catalogue.uom (uom_code, name_en, is_weight)"
                + " values ('CASE', 'Case', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.sku_uom_conversion "
                        + "(sku_id, uom_code, factor_to_base, effective_from,"
                        + " effective_to, owner_entity_id)"
                        + " values (?, 'CASE', 24, ?, null, ?)",
                rice,
                today.minusDays(1),
                FEDERATION);
        admin.update(
                "insert into catalogue.batch "
                        + "(batch_id, sku_id, batch_no, printed_mrp, owner_entity_id)"
                        + " values (?, ?, 'PRC11-CASE', 120.00, ?)",
                Ids.next(),
                rice,
                FEDERATION);

        UUID list = create.handle(new CreatePriceList("TRADE", "Case prices"), own(FEDERATION));

        SetLinesResult result = setLines.handle(
                new SetLines(
                        list,
                        List.of(
                                line(rice, "0", "100"),
                                new SetLines.Line(rice, "CASE", BigDecimal.ZERO, new BigDecimal("2500")),
                                new SetLines.Line(rice, "CASE", new BigDecimal("10"), new BigDecimal("2400")))),
                own(FEDERATION));

        assertThat(result.saved()).isTrue();
        assertThat(result.outcomes()).allSatisfy(outcome -> {
            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.review()).isNull();
        });

        publish.handle(new PublishPriceList(list, today), own(FEDERATION));
        bind(list);

        assertThat(queries.resolveTradePrice(RELATIONSHIP, rice, "CASE", new BigDecimal("9"), today, own(DISTRIBUTOR)))
                .get()
                .satisfies(price -> {
                    assertThat(price.unitPrice()).isEqualByComparingTo("2500");
                    assertThat(price.uomCode()).isEqualTo("CASE");
                    assertThat(price.tierFromQty()).isEqualByComparingTo("0");
                });

        assertThat(queries.resolveTradePrice(RELATIONSHIP, rice, "CASE", new BigDecimal("12"), today, own(DISTRIBUTOR)))
                .get()
                .satisfies(price -> {
                    assertThat(price.unitPrice()).isEqualByComparingTo("2400");
                    assertThat(price.tierFromQty()).isEqualByComparingTo("10");
                });

        assertThat(queries.resolveTradePrice(RELATIONSHIP, rice, "EA", BigDecimal.ONE, today, own(DISTRIBUTOR)))
                .get()
                .satisfies(price -> assertThat(price.unitPrice()).isEqualByComparingTo("100"));
    }

    @Test
    void tradeConversionMustBeInForceOnPublicationDate() {
        JdbcTemplate admin = superuserJdbc();
        admin.update("insert into catalogue.uom (uom_code, name_en, is_weight)"
                + " values ('CASE', 'Case', false) on conflict do nothing");

        UUID list = create.handle(new CreatePriceList("TRADE", "Dated conversion"), own(FEDERATION));

        SetLines.Line caseLine = new SetLines.Line(rice, "CASE", BigDecimal.ZERO, new BigDecimal("2500"));

        SetLinesResult noConversion = setLines.handle(new SetLines(list, List.of(caseLine)), own(FEDERATION));

        assertThat(noConversion.saved()).isFalse();
        assertThat(noConversion.outcomes()).singleElement().satisfies(outcome -> assertThat(outcome.reason())
                .isEqualTo("m3.price_list.line.uom_invalid"));

        admin.update(
                "insert into catalogue.sku_uom_conversion "
                        + "(sku_id, uom_code, factor_to_base, effective_from,"
                        + " effective_to, owner_entity_id)"
                        + " values (?, 'CASE', 24, ?, ?, ?)",
                rice,
                today.minusDays(7),
                today,
                FEDERATION);

        SetLinesResult saved = setLines.handle(new SetLines(list, List.of(caseLine)), own(FEDERATION));
        assertThat(saved.saved()).isTrue();

        refused(
                () -> publish.handle(new PublishPriceList(list, today.plusDays(1)), own(FEDERATION)),
                "m3.price_list.lines_invalid");

        assertThat(queries.getPriceList(list, own(FEDERATION))).get().satisfies(view -> assertThat(view.status())
                .isEqualTo("DRAFT"));
    }

    @Test
    void aSuccessorCannotBeginOnOrBeforeThePreviousVersion() {
        UUID v1 = published(List.of(line(rice, "0", "100")), today);
        UUID v2 = draftNewVersion.handle(new DraftNewVersion(v1), own(FEDERATION));
        kernel.reset();

        refused(
                () -> publish.handle(new PublishPriceList(v2, today), own(FEDERATION)),
                "m3.price_list.apply_from_not_after_previous");

        assertThat(queries.getPriceList(v1, own(FEDERATION))).get().satisfies(view -> assertThat(view.status())
                .isEqualTo("PUBLISHED"));

        assertThat(queries.getPriceList(v2, own(FEDERATION))).get().satisfies(view -> assertThat(view.status())
                .isEqualTo("DRAFT"));

        assertThat(queries.lines(v1, own(FEDERATION)))
                .allSatisfy(line -> assertThat(line.effectiveTo()).isNull());

        assertThat(audit("PRICELIST_PUBLISHED")).isEmpty();
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

    /**
     * M3-09: the handlers read the same pinned clock as this class, at 23:59:59.9 in Colombo, so a
     * list published "from today" is accepted however long the run takes.
     */
    @Test
    void theHandlersReadThePinnedClockAtTheLastTenthOfTheColomboDay() {
        assertThat(clock.instant()).isEqualTo(PinnedClock.INSTANT);
        assertThat(PinnedClock.INSTANT.atZone(PinnedClock.COLOMBO).toLocalTime())
                .isEqualTo(java.time.LocalTime.of(23, 59, 59, 900_000_000));
        UUID list = published(List.of(line(rice, "0", "100")), today);
        assertThat(queries.getPriceList(list, own(FEDERATION))).get().satisfies(v -> assertThat(v.applyFrom())
                .isEqualTo(today));
    }

    /**
     * M3-05: publication locks the list row and checks DRAFT again, so two publishes of one draft
     * started together publish it once: one audit, one event, the other refused as not a draft.
     */
    @Test
    void twoPublishesOfOneDraftAtOnceArePublishedOnce() throws Exception {
        UUID draft = create.handle(new CreatePriceList("TRADE", "Raced"), own(FEDERATION));
        setLines.handle(new SetLines(draft, List.of(line(rice, "0", "100"))), own(FEDERATION));
        kernel.reset();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            List<Future<String>> running = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                running.add(pool.submit(() -> {
                    start.await();
                    try {
                        publish.handle(new PublishPriceList(draft, today), own(FEDERATION));
                        return "published";
                    } catch (ProblemException refused) {
                        return refused.messageId();
                    }
                }));
            }
            start.countDown();
            for (Future<String> result : running) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).containsExactlyInAnyOrder("published", "m3.price_list.not_draft");
        assertThat(audit("PRICELIST_PUBLISHED")).hasSize(1);
        assertThat(events(PriceListPublished.class)).hasSize(1);
    }

    @Test
    void aPublishedLineCannotBeChangedEvenBehindTheHandlers() {
        UUID list = published(List.of(line(rice, "0", "100")), today);
        assertThatThrownBy(() ->
                        superuserJdbc().update("delete from pricing.price_list_line where price_list_id = ?", list))
                .hasMessageContaining("is not a draft");
    }

    @Test
    void tradePriceListPublishedAboveMrpRaisesReview() {
        UUID batchId = Ids.next();
        superuserJdbc()
                .update(
                        "insert into catalogue.batch (batch_id, sku_id, batch_no, printed_mrp, owner_entity_id)"
                                + " values (?, ?, 'MRP90', 90.00, ?)",
                        batchId,
                        rice,
                        FEDERATION);

        UUID location = UUID.fromString("0190e760-0000-7000-8000-000000000101");
        superuserJdbc()
                .update(
                        "insert into inventory.stock_lot (stock_lot_id, sku_id, location_id, condition, qty_on_hand, unit_cost, received_at, owner_entity_id, batch_id)"
                                + " values (?, ?, ?, 'GOOD', 10.000, 90.00, now(), ?, ?)",
                        Ids.next(),
                        rice,
                        location,
                        FEDERATION,
                        batchId);
        kernel.reset();

        UUID list = create.handle(new CreatePriceList("TRADE", "Above MRP"), own(FEDERATION));
        setLines.handle(new SetLines(list, List.of(line(rice, "0", "95.00"))), own(FEDERATION));
        publish.handle(new PublishPriceList(list, today), own(FEDERATION));

        assertThat(audit("TRADE_ABOVE_MRP")).singleElement().satisfies(r -> {
            assertThat(r.subject().id()).isEqualTo(rice);
            Map<?, ?> details = (Map<?, ?>) r.after();
            assertThat(details.get("priceListId").toString()).isEqualTo(list.toString());
            assertThat(new BigDecimal(details.get("price").toString())).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(new BigDecimal(details.get("mrp").toString())).isEqualByComparingTo(new BigDecimal("90.00"));
        });

        assertThat(events(lk.coopfed.knoweb.m3pricing.api.ReviewRaised.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo("TRADE_ABOVE_MRP");
                    assertThat(e.ownerEntityId()).isEqualTo(FEDERATION);
                    assertThat(e.skuId()).isEqualTo(rice);
                    assertThat(e.details()).isEqualTo(list.toString());
                });

        // Compliant scenario
        UUID list2 = create.handle(new CreatePriceList("TRADE", "Below MRP"), own(FEDERATION));
        setLines.handle(new SetLines(list2, List.of(line(rice, "0", "85.00"))), own(FEDERATION));
        publish.handle(new PublishPriceList(list2, today), own(FEDERATION));

        assertThat(audit("TRADE_ABOVE_MRP")).hasSize(1); // Still 1 from before
    }

    @Autowired
    lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry config;

    @Test
    void tiersAboveTheMrpRaiseOneReviewPerItemAndNoneWhenTheReviewIsSwitchedOff() {
        UUID batchId = Ids.next();
        superuserJdbc()
                .update(
                        "insert into catalogue.batch (batch_id, sku_id, batch_no, printed_mrp, owner_entity_id)"
                                + " values (?, ?, 'MRP90T', 90.00, ?)",
                        batchId,
                        rice,
                        FEDERATION);
        superuserJdbc()
                .update(
                        "insert into inventory.stock_lot (stock_lot_id, sku_id, location_id, condition, qty_on_hand,"
                                + " unit_cost, received_at, owner_entity_id, batch_id)"
                                + " values (?, ?, ?, 'GOOD', 10.000, 90.00, now(), ?, ?)",
                        Ids.next(),
                        rice,
                        UUID.fromString("0190e760-0000-7000-8000-000000000101"),
                        FEDERATION,
                        batchId);
        kernel.reset();

        // M3R-06: tier 0 at 95 and tier 10 at 92, both above the MRP of 90: one review, the highest price.
        UUID list = create.handle(new CreatePriceList("TRADE", "Tiers above MRP"), own(FEDERATION));
        setLines.handle(
                new SetLines(list, List.of(line(rice, "0", "95.00"), line(rice, "10", "92.00"))), own(FEDERATION));
        publish.handle(new PublishPriceList(list, today), own(FEDERATION));

        assertThat(audit("TRADE_ABOVE_MRP")).singleElement().satisfies(r -> {
            Map<?, ?> details = (Map<?, ?>) r.after();
            assertThat(new BigDecimal(details.get("price").toString())).isEqualByComparingTo("95.00");
            assertThat(new BigDecimal(details.get("tierFromQty").toString())).isEqualByComparingTo("0");
        });
        assertThat(events(lk.coopfed.knoweb.m3pricing.api.ReviewRaised.class)).hasSize(1);
        // Guards, mutation, audit, events: the review follows the publication in both lists.
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsSubsequence("PRICELIST_PUBLISHED", "TRADE_ABOVE_MRP");

        // pricing.trade_above_mrp_review false: published, no review (M3R-10).
        superuserJdbc()
                .update("insert into kernel.config_value (key, scope_entity_id, scope_location_id, value, changed_by)"
                        + " values ('pricing.trade_above_mrp_review', null, null, 'false'::jsonb, gen_random_uuid())");
        config.invalidate("pricing.trade_above_mrp_review");
        try {
            kernel.reset();
            UUID quiet = create.handle(new CreatePriceList("TRADE", "Review off"), own(FEDERATION));
            setLines.handle(new SetLines(quiet, List.of(line(rice, "0", "95.00"))), own(FEDERATION));
            publish.handle(new PublishPriceList(quiet, today), own(FEDERATION));
            assertThat(audit("PRICELIST_PUBLISHED")).hasSize(1);
            assertThat(audit("TRADE_ABOVE_MRP")).isEmpty();
            assertThat(events(lk.coopfed.knoweb.m3pricing.api.ReviewRaised.class))
                    .isEmpty();
        } finally {
            superuserJdbc().update("delete from kernel.config_value where key = 'pricing.trade_above_mrp_review'");
            config.invalidate("pricing.trade_above_mrp_review");
        }
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

    /**
     * m3pricing V0006 (wave 2, RLS-05): buyer_read admits the buyer of an ACTIVE relationship only. A
     * suspended or replaced buyer cannot order, and its invoices are the record of the price it was
     * charged; the live list is the seller's. Read directly under the policy, in the buyer's OWN and
     * PARTY scopes, so the rule is proved in SQL and not in M1's lookup.
     */
    @Test
    void aSuspendedOrReplacedBuyerNoLongerReadsTheTradeList() {
        UUID list = published(List.of(line(rice, "0", "100")), today);
        bind(list);

        assertThat(listsVisibleTo(DISTRIBUTOR, "OWN")).containsExactly(list);
        assertThat(listsVisibleTo(DISTRIBUTOR, "PARTY")).containsExactly(list);
        assertThat(linesVisibleTo(DISTRIBUTOR, "OWN")).isEqualTo(1);

        for (String status : List.of("SUSPENDED", "REPLACED", "DRAFT")) {
            superuserJdbc()
                    .update(
                            "update party.entity_relationship set status = ? where relationship_id = ?",
                            status,
                            RELATIONSHIP);
            assertThat(listsVisibleTo(DISTRIBUTOR, "OWN")).as(status).isEmpty();
            assertThat(listsVisibleTo(DISTRIBUTOR, "PARTY")).as(status).isEmpty();
            assertThat(linesVisibleTo(DISTRIBUTOR, "OWN")).as(status + " lines").isZero();
        }

        superuserJdbc()
                .update(
                        "update party.entity_relationship set status = 'ACTIVE' where relationship_id = ?",
                        RELATIONSHIP);
        assertThat(listsVisibleTo(DISTRIBUTOR, "OWN")).containsExactly(list);
        assertThat(listsVisibleTo(STRANGER, "OWN")).as("no relationship at all").isEmpty();
    }

    private List<UUID> listsVisibleTo(UUID entity, String policyClass) {
        return inScope(
                entity,
                policyClass,
                () -> jdbc.queryForList(
                        "select price_list_id from pricing.price_list where kind = 'TRADE'", UUID.class));
    }

    private int linesVisibleTo(UUID entity, String policyClass) {
        return inScope(
                entity,
                policyClass,
                () -> jdbc.queryForObject("select count(*) from pricing.price_list_line", Integer.class));
    }

    private <T> T inScope(UUID entity, String policyClass, java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', '', true),"
                            + " set_config('app.scope_class', ?, true), set_config('app.granted_entities', '{}', true)",
                    entity.toString(),
                    policyClass);
            try {
                return work.get();
            } finally {
                status.setRollbackOnly();
            }
        });
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

    @Test
    void commercialPriceListCreationAndPublishing() {
        UUID commercialList = create.handle(new CreatePriceList("COMMERCIAL", "Commercial list"), own(FEDERATION));
        assertThat(commercialList).isNotNull();
        assertThat(audit("PRICELIST_DRAFTED")).hasSize(1);
        assertThat(events(PriceListDrafted.class))
                .containsExactly(new PriceListDrafted(commercialList, commercialList, FEDERATION, "COMMERCIAL", 1));
        kernel.reset();

        SetLinesResult result = setLines.handle(
                new SetLines(commercialList, List.of(line(rice, "0", "120.00"), line(sugar, "0", "150.00"))),
                own(FEDERATION));
        assertThat(result.saved()).isTrue();
        kernel.reset();

        publish.handle(new PublishPriceList(commercialList, today), own(FEDERATION));
        assertThat(queries.getPriceList(commercialList, own(FEDERATION))).get().satisfies(view -> {
            assertThat(view.status()).isEqualTo("PUBLISHED");
            assertThat(view.kind()).isEqualTo("COMMERCIAL");
            assertThat(view.applyFrom()).isEqualTo(today);
        });

        // Creating a second commercial price list draft for the same entity fails
        refused(
                () -> create.handle(new CreatePriceList("COMMERCIAL", "Another list"), own(FEDERATION)),
                "m3.price_list.commercial_exists");
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
