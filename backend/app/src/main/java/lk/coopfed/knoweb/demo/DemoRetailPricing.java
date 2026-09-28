package lk.coopfed.knoweb.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lk.coopfed.knoweb.demo.DemoCast.Actor;
import lk.coopfed.knoweb.demo.DemoCatalogue.Item;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.EnterControlPrice;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult;
import lk.coopfed.knoweb.m3pricing.api.SetMrpPolicy;
import lk.coopfed.knoweb.m3pricing.query.MrpPolicyView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The demo's shelf prices (M3-06, M3-07; docs/PLAN_TO_M2.md "DEMO, pricing data"): the Federation's
 * pricing officer enters control prices for three gazetted items, Kuliyapitiya MPCS's manager sets
 * the picker policy for milk powder and publishes the society's shelf price list for its shops. In a
 * class of its own that {@link DemoDataLoader} calls on the set-up day, after the society's opening
 * stock (the shelf list is checked against the lowest printed MRP in stock at its locations).
 *
 * <p>Each shelf price is the pack's printed MRP, or for loose goods the distributor's price plus a
 * tenth rounded to the rupee, and never above the control price: so the list passes the ceilings
 * it is checked against, and the gazetted items show their control price on the shelf.
 *
 * <p>Idempotent: a SKU that has a control price gets none, a policy already the society's is not
 * set again, and a society with a published shelf list gets no other.
 */
@Service
class DemoRetailPricing {

    /** A gazetted item of the demo: its English name, the ceiling in its base unit, the gazette. */
    record Gazetted(String nameEn, BigDecimal ceiling, String gazette) {}

    static final List<Gazetted> GAZETTED = List.of(
            new Gazetted("Nadu rice 5 kg", new BigDecimal("1100.00"), "2492/29"),
            new Gazetted("White sugar 1 kg", new BigDecimal("275.00"), "2492/30"),
            new Gazetted("Samba rice, loose", new BigDecimal("250.00"), "2492/31"));

    static final String PICKER_ITEM = "Full cream milk powder 400 g";
    static final BigDecimal PICKER_GAP = new BigDecimal("20.00");
    static final String SHELF_LIST_NAME = "Kuliyapitiya shelf prices";

    private static final BigDecimal LOOSE_MARGIN = new BigDecimal("1.10");

    private final Handles<EnterControlPrice, UUID> enterControlPrice;
    private final Handles<SetMrpPolicy, UUID> setMrpPolicy;
    private final Handles<CreatePriceList, UUID> createPriceList;
    private final Handles<SetLines, SetLinesResult> setLines;
    private final Handles<PublishPriceList, UUID> publishPriceList;
    private final PricingQueries pricing;
    private final Clock clock;
    private final ZoneId businessZone;

    DemoRetailPricing(
            Handles<EnterControlPrice, UUID> enterControlPrice,
            Handles<SetMrpPolicy, UUID> setMrpPolicy,
            Handles<CreatePriceList, UUID> createPriceList,
            Handles<SetLines, SetLinesResult> setLines,
            Handles<PublishPriceList, UUID> publishPriceList,
            PricingQueries pricing,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.enterControlPrice = enterControlPrice;
        this.setMrpPolicy = setMrpPolicy;
        this.createPriceList = createPriceList;
        this.setLines = setLines;
        this.publishPriceList = publishPriceList;
        this.pricing = pricing;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    /** Control prices, the milk powder policy and the published shelf list, whatever of them is missing. */
    void load(List<Item> items, Map<String, UUID> skus, Consumer<String> count) {
        controlPrices(items, skus, count);
        pickerPolicy(skus, count);
        shelfList(items, skus, count);
    }

    private void controlPrices(List<Item> items, Map<String, UUID> skus, Consumer<String> count) {
        ScopeContext scope = scopeOf(DemoCast.FED_PRICING);
        for (Gazetted gazetted : GAZETTED) {
            UUID sku = required(skus, gazetted.nameEn());
            if (!pricing.controlPrices(sku, scope).isEmpty()) {
                continue;
            }
            Item item = item(items, gazetted.nameEn());
            enterControlPrice.handle(
                    new EnterControlPrice(sku, gazetted.ceiling(), item.baseUom(), today(), null, gazetted.gazette()),
                    scope);
            count.accept("EnterControlPrice");
        }
    }

    private void pickerPolicy(Map<String, UUID> skus, Consumer<String> count) {
        ScopeContext scope = scopeOf(DemoCast.M101_MANAGER);
        UUID sku = required(skus, PICKER_ITEM);
        if (MrpPolicyView.OWN.equals(pricing.effectiveMrpPolicy(sku, scope).source())) {
            return;
        }
        setMrpPolicy.handle(new SetMrpPolicy(sku, "PICKER", PICKER_GAP, null), scope);
        count.accept("SetMrpPolicy");
    }

    private void shelfList(List<Item> items, Map<String, UUID> skus, Consumer<String> count) {
        ScopeContext scope = scopeOf(DemoCast.M101_MANAGER);
        List<PriceListView> retail = pricing.listPriceLists("RETAIL", null, scope).stream()
                .filter(list -> DemoCast.M101.equals(list.ownerEntityId()))
                .toList();
        if (retail.stream().anyMatch(list -> "PUBLISHED".equals(list.status()))) {
            return;
        }
        UUID draft = retail.stream()
                .filter(list -> "DRAFT".equals(list.status()))
                .map(PriceListView::priceListId)
                .findFirst()
                .orElse(null);
        if (draft == null) {
            draft = createPriceList.handle(new CreatePriceList("RETAIL", SHELF_LIST_NAME), scope);
            count.accept("CreatePriceList");
        }
        List<SetLines.Line> lines = new ArrayList<>();
        for (Item item : items) {
            lines.add(new SetLines.Line(
                    required(skus, item.nameEn()), item.baseUom(), BigDecimal.ZERO, shelfPrice(item)));
        }
        SetLinesResult result = setLines.handle(new SetLines(draft, lines), scope);
        count.accept("SetLines");
        if (!result.saved()) {
            throw new IllegalStateException("Demo shelf price list refused: " + result);
        }
        publishPriceList.handle(new PublishPriceList(draft, today()), scope);
        count.accept("PublishPriceList");
    }

    /** The printed MRP, or the distributor's price and a tenth for loose goods; never above the control price. */
    static BigDecimal shelfPrice(Item item) {
        BigDecimal price = item.hasPrintedMrp() && item.printedMrp() != null
                ? item.printedMrp()
                : item.distributorPrice().multiply(LOOSE_MARGIN).setScale(0, RoundingMode.HALF_UP);
        Optional<Gazetted> gazetted =
                GAZETTED.stream().filter(g -> g.nameEn().equals(item.nameEn())).findFirst();
        if (gazetted.isPresent() && gazetted.get().ceiling().compareTo(price) < 0) {
            price = gazetted.get().ceiling();
        }
        return price.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static Item item(List<Item> items, String nameEn) {
        return items.stream()
                .filter(item -> item.nameEn().equals(nameEn))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Demo catalogue has no item " + nameEn));
    }

    private static UUID required(Map<String, UUID> skus, String nameEn) {
        UUID sku = skus.get(nameEn);
        if (sku == null) {
            throw new IllegalStateException("Demo catalogue has no item " + nameEn);
        }
        return sku;
    }

    /** As DemoDataLoader's: the user's own scope, with the second factor just presented. */
    private ScopeContext scopeOf(Actor actor) {
        Scope scope = new Scope(actor.entityId(), actor.locationId());
        return new ScopeContext(
                actor.userId(),
                null,
                actor.entityId(),
                List.of(scope),
                scope,
                PolicyClass.OWN,
                Set.of(),
                clock.instant(),
                Locale.ENGLISH,
                null);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), businessZone);
    }
}
