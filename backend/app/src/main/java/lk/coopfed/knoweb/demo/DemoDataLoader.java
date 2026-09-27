package lk.coopfed.knoweb.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.demo.DemoCast.Actor;
import lk.coopfed.knoweb.demo.DemoCast.Distributor;
import lk.coopfed.knoweb.demo.DemoCast.Shop;
import lk.coopfed.knoweb.demo.DemoCatalogue.Item;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.ActivateRelationship;
import lk.coopfed.knoweb.m1party.api.OpenTradingRelationship;
import lk.coopfed.knoweb.m1party.api.RegisterTillPosition;
import lk.coopfed.knoweb.m1party.api.SetPrimaryTill;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipSide;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m1party.query.TillPositionView;
import lk.coopfed.knoweb.m2catalogue.api.ActivateSharedSku;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.DefineConversion;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBarcode;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m2catalogue.query.TaxCategoryView;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m5inventory.api.CountersignOpeningBalance;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceLine;
import lk.coopfed.knoweb.m5inventory.api.PrepareOpeningBalance;
import lk.coopfed.knoweb.m5inventory.api.SignOpeningBalance;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sets up the demo (docs/DEMO.md) through the modules' own command handlers, each command run as
 * the demo user whose job it is: the catalogue steward creates the SKUs, the pricing officer
 * publishes the list and opens the relationships, the stores user prepares and signs the opening
 * stock and a second person countersigns it. So every row carries its audit record, its event,
 * its document number and its ledger movements, exactly as if a person had done it on the screens.
 *
 * <p>Idempotent: every step first asks the module's query whether its result is already there
 * and skips it when it is, so a second run issues no command at all ({@link Report#total()} is
 * zero). The parties and users it acts as are rows of the demo seed (seed/m1party/demo-parties.demo.sql,
 * seed/m1security/demo-users.demo.sql), which must be loaded first.
 *
 * <p>M4 (orders, delivery notes, GRN, invoices) is not loaded: the storyline starts those on the
 * screens, and the demo documents of M4 come when M4 is on main (docs/DEMO.md, "Still to come").
 */
@Service
public class DemoDataLoader {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    /** The conversions and barcodes date from the start of the demo year, the same on every run. */
    private static final LocalDate CATALOGUE_FROM = LocalDate.of(2026, 1, 1);

    private static final BigDecimal BULK_FROM = new BigDecimal("100");
    private static final BigDecimal SOCIETY_BULK_FROM = new BigDecimal("20");
    private static final BigDecimal SOCIETY_BULK_DISCOUNT = new BigDecimal("0.98");

    /** What one run did: the number of commands of each kind. Zero everywhere on a second run. */
    public record Report(Map<String, Integer> commands) {

        public int total() {
            return commands.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final Handles<RegisterTillPosition, UUID> registerTillPosition;
    private final Handles<SetPrimaryTill, UUID> setPrimaryTill;
    private final Handles<CreateSku, UUID> createSku;
    private final Handles<ActivateSharedSku, UUID> activateSharedSku;
    private final Handles<DefineConversion, UUID> defineConversion;
    private final Handles<RegisterBarcode, UUID> registerBarcode;
    private final Handles<CreatePriceList, UUID> createPriceList;
    private final Handles<SetLines, SetLinesResult> setLines;
    private final Handles<PublishPriceList, UUID> publishPriceList;
    private final Handles<OpenTradingRelationship, UUID> openRelationship;
    private final Handles<ActivateRelationship, UUID> activateRelationship;
    private final Handles<PrepareOpeningBalance, UUID> prepareOpeningBalance;
    private final Handles<SignOpeningBalance, UUID> signOpeningBalance;
    private final Handles<CountersignOpeningBalance, UUID> countersignOpeningBalance;
    private final PartyQueries party;
    private final RelationshipQueries relationships;
    private final CatalogueQueries catalogue;
    private final PricingQueries pricing;
    private final InventoryQueries inventory;
    private final Clock clock;
    private final ZoneId businessZone;

    private Map<String, Integer> counts;

    @SuppressWarnings("java:S107") // one handler per command the demo issues; a loader, not a design
    public DemoDataLoader(
            Handles<RegisterTillPosition, UUID> registerTillPosition,
            Handles<SetPrimaryTill, UUID> setPrimaryTill,
            Handles<CreateSku, UUID> createSku,
            Handles<ActivateSharedSku, UUID> activateSharedSku,
            Handles<DefineConversion, UUID> defineConversion,
            Handles<RegisterBarcode, UUID> registerBarcode,
            Handles<CreatePriceList, UUID> createPriceList,
            Handles<SetLines, SetLinesResult> setLines,
            Handles<PublishPriceList, UUID> publishPriceList,
            Handles<OpenTradingRelationship, UUID> openRelationship,
            Handles<ActivateRelationship, UUID> activateRelationship,
            Handles<PrepareOpeningBalance, UUID> prepareOpeningBalance,
            Handles<SignOpeningBalance, UUID> signOpeningBalance,
            Handles<CountersignOpeningBalance, UUID> countersignOpeningBalance,
            PartyQueries party,
            RelationshipQueries relationships,
            CatalogueQueries catalogue,
            PricingQueries pricing,
            InventoryQueries inventory,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.registerTillPosition = registerTillPosition;
        this.setPrimaryTill = setPrimaryTill;
        this.createSku = createSku;
        this.activateSharedSku = activateSharedSku;
        this.defineConversion = defineConversion;
        this.registerBarcode = registerBarcode;
        this.createPriceList = createPriceList;
        this.setLines = setLines;
        this.publishPriceList = publishPriceList;
        this.openRelationship = openRelationship;
        this.activateRelationship = activateRelationship;
        this.prepareOpeningBalance = prepareOpeningBalance;
        this.signOpeningBalance = signOpeningBalance;
        this.countersignOpeningBalance = countersignOpeningBalance;
        this.party = party;
        this.relationships = relationships;
        this.catalogue = catalogue;
        this.pricing = pricing;
        this.inventory = inventory;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    /** Loads whatever of the demo is missing, in the order of the storyline. */
    public synchronized Report load() {
        counts = new LinkedHashMap<>();
        List<Item> items = DemoCatalogue.load();

        tills();
        Map<String, UUID> skus = catalogue(items);
        UUID federationList = federationPriceList(items, skus);
        for (Distributor distributor : DemoCast.DISTRIBUTORS) {
            relationship(
                    DemoCast.FED_PRICING, distributor.entityId(), federationList, new BigDecimal("25000000.00"), 45);
        }
        for (Distributor distributor : DemoCast.DISTRIBUTORS) {
            UUID list = distributorPriceList(distributor, items, skus);
            for (UUID society : distributor.societies()) {
                relationship(distributor.commercial(), society, list, new BigDecimal("5000000.00"), 30);
            }
        }
        openingStock(DemoCast.FED_STORES, DemoCast.FED_ACCOUNTS, items, skus, true);
        for (Distributor distributor : DemoCast.DISTRIBUTORS) {
            openingStock(distributor.stores(), distributor.accounts(), items, skus, false);
        }

        Report report = new Report(Map.copyOf(counts));
        log.info("Demo data: {} commands issued {}", report.total(), report.commands());
        return report;
    }

    // ---- M1: till positions and the primary till of every shop -----------------------------

    private void tills() {
        for (Shop shop : DemoCast.SHOPS) {
            ScopeContext scope = scopeOf(shop.manager());
            List<TillPositionView> existing = party.listTillPositions(shop.locationId(), scope);
            UUID first = existing.stream()
                    .filter(p -> p.positionNo() == 1)
                    .map(TillPositionView::tillPositionId)
                    .findFirst()
                    .orElse(null);
            for (int no = 1; no <= shop.tills(); no++) {
                int positionNo = no;
                if (existing.stream().noneMatch(p -> p.positionNo() == positionNo)) {
                    UUID id =
                            registerTillPosition.handle(new RegisterTillPosition(shop.locationId(), positionNo), scope);
                    count("RegisterTillPosition");
                    if (positionNo == 1) {
                        first = id;
                    }
                }
            }
            LocationView location = party.getLocation(shop.locationId(), scope)
                    .orElseThrow(() -> new IllegalStateException("Demo shop " + shop.locationId()
                            + " not found: load the demo seed first (make demo-data)"));
            if (location.primaryTillPositionId() == null) {
                setPrimaryTill.handle(
                        new SetPrimaryTill(shop.locationId(), first, "OPENING", "Demo: the shop's first till"), scope);
                count("SetPrimaryTill");
            }
        }
    }

    // ---- M2: the Federation's shared catalogue ----------------------------------------------

    private Map<String, UUID> catalogue(List<Item> items) {
        ScopeContext steward = scopeOf(DemoCast.FED_STEWARD);
        Map<String, UUID> taxCategories = new HashMap<>();
        for (TaxCategoryView tax : catalogue.taxCategories(steward)) {
            taxCategories.put(tax.code(), tax.taxCategoryId());
        }
        Map<String, SkuView> existing = federationSkus(steward);

        Map<String, UUID> ids = new LinkedHashMap<>();
        for (Item item : items) {
            SkuView found = existing.get(item.nameEn());
            UUID skuId;
            String status;
            if (found == null) {
                UUID tax = taxCategories.get(item.taxCode());
                if (tax == null) {
                    throw new IllegalStateException("Demo catalogue: no tax category " + item.taxCode());
                }
                skuId = createSku.handle(new CreateSku(details(item, tax)), steward);
                count("CreateSku");
                status = "DRAFT";
            } else {
                skuId = found.skuId();
                status = found.status();
            }
            if ("DRAFT".equals(status)) {
                activateSharedSku.handle(new ActivateSharedSku(skuId), steward);
                count("ActivateSharedSku");
            }
            if (item.packUom() != null
                    && catalogue.conversions(skuId, steward).stream()
                            .noneMatch(c -> c.uomCode().equals(item.packUom()))) {
                defineConversion.handle(
                        new DefineConversion(skuId, item.packUom(), item.packFactor(), CATALOGUE_FROM, null), steward);
                count("DefineConversion");
            }
            String barcode = item.barcode();
            if (barcode != null
                    && catalogue.barcodes(skuId, steward).stream()
                            .noneMatch(b -> b.barcode().equals(barcode))) {
                registerBarcode.handle(new RegisterBarcode(skuId, barcode, "EAN13", item.baseUom(), null), steward);
                count("RegisterBarcode");
            }
            ids.put(item.nameEn(), skuId);
        }
        return ids;
    }

    private Map<String, SkuView> federationSkus(ScopeContext scope) {
        Map<String, SkuView> byName = new HashMap<>();
        int offset = 0;
        while (true) {
            SkuPage page = catalogue.listSkus(new SkuFilter(null, null, "en", offset, 200), scope);
            for (SkuView sku : page.items()) {
                if (DemoCast.FEDERATION.equals(sku.ownerEntityId())) {
                    byName.putIfAbsent(sku.nameEn(), sku);
                }
            }
            if (page.nextOffset() == null || page.items().isEmpty()) {
                return byName;
            }
            offset = page.nextOffset();
        }
    }

    private static SkuDetails details(Item item, UUID taxCategoryId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("demo", true);
        return new SkuDetails(
                item.nameEn(),
                item.nameSi(),
                item.nameTa(),
                null,
                null,
                null,
                item.baseUom(),
                item.soldByWeight(),
                item.batchTracked(),
                item.expiryTracked(),
                item.hasPrintedMrp(),
                item.expiryTracked() ? (short) 30 : null,
                taxCategoryId,
                null,
                null,
                attributes);
    }

    // ---- M3: the trade price lists ------------------------------------------------------------

    private UUID federationPriceList(List<Item> items, Map<String, UUID> skus) {
        List<SetLines.Line> lines = new ArrayList<>();
        for (Item item : items) {
            UUID sku = skus.get(item.nameEn());
            lines.add(new SetLines.Line(sku, item.baseUom(), BigDecimal.ZERO, item.federationPrice()));
            lines.add(new SetLines.Line(sku, item.baseUom(), BULK_FROM, item.federationBulkPrice()));
        }
        return priceList(DemoCast.FED_PRICING, DemoCast.FEDERATION_LIST_NAME, lines);
    }

    private UUID distributorPriceList(Distributor distributor, List<Item> items, Map<String, UUID> skus) {
        List<SetLines.Line> lines = new ArrayList<>();
        for (Item item : items) {
            UUID sku = skus.get(item.nameEn());
            BigDecimal price = item.distributorPrice();
            lines.add(new SetLines.Line(sku, item.baseUom(), BigDecimal.ZERO, price));
            lines.add(new SetLines.Line(
                    sku,
                    item.baseUom(),
                    SOCIETY_BULK_FROM,
                    price.multiply(SOCIETY_BULK_DISCOUNT).setScale(2, java.math.RoundingMode.HALF_UP)));
        }
        return priceList(distributor.commercial(), distributor.listName(), lines);
    }

    /** The root id of the named TRADE list of the actor's entity, published with these lines. */
    private UUID priceList(Actor actor, String name, List<SetLines.Line> lines) {
        ScopeContext scope = scopeOf(actor);
        List<PriceListView> named = pricing.listPriceLists("TRADE", null, scope).stream()
                .filter(list -> name.equals(list.name()) && actor.entityId().equals(list.ownerEntityId()))
                .toList();
        Optional<PriceListView> published =
                named.stream().filter(list -> "PUBLISHED".equals(list.status())).findFirst();
        if (published.isPresent()) {
            return published.get().rootPriceListId();
        }
        UUID draft = named.stream()
                .filter(list -> "DRAFT".equals(list.status()))
                .map(PriceListView::priceListId)
                .findFirst()
                .orElse(null);
        if (draft == null) {
            draft = createPriceList.handle(new CreatePriceList("TRADE", name), scope);
            count("CreatePriceList");
        }
        SetLinesResult result = setLines.handle(new SetLines(draft, lines), scope);
        count("SetLines");
        if (!result.saved()) {
            throw new IllegalStateException("Demo price list '" + name + "' refused: " + result);
        }
        publishPriceList.handle(new PublishPriceList(draft, today()), scope);
        count("PublishPriceList");
        return pricing.getPriceList(draft, scope)
                .map(PriceListView::rootPriceListId)
                .orElse(draft);
    }

    // ---- M1: the trading relationships -----------------------------------------------------------

    private void relationship(Actor seller, UUID buyer, UUID priceListId, BigDecimal creditLimit, int paymentDays) {
        ScopeContext scope = scopeOf(seller);
        List<RelationshipView> pair = relationships.listRelationships(RelationshipSide.SELLER, scope).stream()
                .filter(r ->
                        buyer.equals(r.buyerEntityId()) && seller.entityId().equals(r.sellerEntityId()))
                .toList();
        if (pair.stream().anyMatch(r -> "ACTIVE".equals(r.status()))) {
            return;
        }
        UUID id = pair.stream()
                .filter(r -> "DRAFT".equals(r.status()))
                .map(RelationshipView::relationshipId)
                .findFirst()
                .orElse(null);
        if (id == null) {
            id = openRelationship.handle(
                    new OpenTradingRelationship(
                            buyer, priceListId, creditLimit, paymentDays, 7, 24, "FCFS", today(), null),
                    scope);
            count("OpenTradingRelationship");
        }
        activateRelationship.handle(new ActivateRelationship(id), scope);
        count("ActivateRelationship");
    }

    // ---- M5: opening stock, prepared and signed by stores, countersigned by accounts ---------------

    private void openingStock(
            Actor stores, Actor countersigner, List<Item> items, Map<String, UUID> skus, boolean federation) {
        ScopeContext scope = scopeOf(stores);
        if (!inventory.skusWithLots(stores.locationId(), scope).isEmpty()) {
            return;
        }
        LocalDate today = today();
        List<OpeningBalanceLine> lines = new ArrayList<>();
        for (Item item : items) {
            BigDecimal qty = federation ? item.federationQty() : item.distributorQty();
            // A distributor's stock was bought from the Federation: its cost is the Federation's price.
            BigDecimal cost = federation ? item.unitCost() : item.federationPrice();
            String batchNo = item.batchTracked() ? "DEMO-" + today.getYear() + "-" + item.lineNo() : null;
            LocalDate expiry = item.expiryTracked() ? today.plusDays(item.shelfLifeDays()) : null;
            lines.add(new OpeningBalanceLine(
                    null,
                    LotCondition.GOOD,
                    qty,
                    cost,
                    skus.get(item.nameEn()),
                    batchNo,
                    expiry,
                    item.hasPrintedMrp() ? item.printedMrp() : null));
        }
        UUID balance = prepareOpeningBalance.handle(new PrepareOpeningBalance(stores.locationId(), lines), scope);
        count("PrepareOpeningBalance");
        signOpeningBalance.handle(new SignOpeningBalance(balance), scope);
        count("SignOpeningBalance");
        countersignOpeningBalance.handle(new CountersignOpeningBalance(balance), scopeOf(countersigner));
        count("CountersignOpeningBalance");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /**
     * The scope a demo user would have after signing in: OWN, in the user's entity, at the user's
     * location or entity-wide, with the second factor just presented (the loader stands for a person
     * at the screen, and publishing a list or signing a balance asks for it). The permission check
     * runs against the user's roles as for any request.
     */
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

    private void count(String command) {
        counts.merge(command, 1, Integer::sum);
    }
}
