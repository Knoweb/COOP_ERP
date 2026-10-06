package lk.coopfed.knoweb.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lk.coopfed.knoweb.demo.DemoCast.Actor;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.ActivateLocalSku;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m2catalogue.query.TaxCategoryView;
import lk.coopfed.knoweb.m5inventory.api.ApproveAdjustment;
import lk.coopfed.knoweb.m5inventory.api.ApproveWriteOff;
import lk.coopfed.knoweb.m5inventory.api.DefineRecipe;
import lk.coopfed.knoweb.m5inventory.api.ExecuteRepack;
import lk.coopfed.knoweb.m5inventory.api.LossCategory;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.RequestWriteOff;
import lk.coopfed.knoweb.m5inventory.api.ScheduleCount;
import lk.coopfed.knoweb.m5inventory.api.StartCount;
import lk.coopfed.knoweb.m5inventory.api.SubmitCount;
import lk.coopfed.knoweb.m5inventory.api.SubmitWriteOff;
import lk.coopfed.knoweb.m5inventory.api.WitnessWriteOff;
import lk.coopfed.knoweb.m5inventory.query.CountView;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.RecipeView;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.m5inventory.query.WriteOffView;
import org.springframework.stereotype.Service;

/**
 * Stock control at Kuliyapitiya MPCS's stores (M5-11, M5-13 and the repack), dated in the demo
 * history (DEMO-02, {@link DemoCalendar}): three weeks ago the stores counted dhal and sugar and
 * found a small shortfall, which the manager approved; twelve days ago the buyer wrote off damaged
 * wheat flour, which the manager witnessed and approved; six days ago the stores repacked loose
 * samba rice into the society's own 5 kg packs. Every step goes through the ordinary handlers as
 * the demo user whose job it is: the buyer (m101-buyer) counts, requests and repacks; the manager
 * (m101-manager) approves, witnesses and sets up the society's own pack.
 *
 * <p>Idempotent: each story first looks for what it left at the stores and carries on from there
 * (a count left in review is approved, a write-off left half way is finished); a second run
 * issues nothing.
 */
@Service
class DemoStockOperations {

    static final String COUNTED_ITEM_SHORT_ONE = "Red dhal 1 kg";
    static final String COUNTED_ITEM_SHORT_THREE = "White sugar 1 kg";
    static final String DAMAGED_ITEM = "Wheat flour 1 kg";
    static final String LOOSE_RICE = "Samba rice, loose";
    static final String SOCIETY_PACK = "Samba rice 5 kg, society pack";
    static final String RECIPE = "Loose samba rice into 5 kg packs";

    private static final BigDecimal PACK_KG = new BigDecimal("5");

    /** The society office (Shanthi Wijeratne), the write-off's in-person witness; as DemoCustomers has her. */
    private static final Actor M101_OFFICE =
            new Actor("m101-office", UUID.fromString("0190f0de-0000-7000-8000-000000000234"), DemoCast.M101, null);

    private final Handles<ScheduleCount, UUID> scheduleCount;
    private final Handles<StartCount, UUID> startCount;
    private final Handles<SubmitCount, UUID> submitCount;
    private final Handles<ApproveAdjustment, UUID> approveAdjustment;
    private final Handles<RequestWriteOff, UUID> requestWriteOff;
    private final Handles<SubmitWriteOff, UUID> submitWriteOff;
    private final Handles<WitnessWriteOff, UUID> witnessWriteOff;
    private final Handles<ApproveWriteOff, UUID> approveWriteOff;
    private final Handles<CreateSku, UUID> createSku;
    private final Handles<ActivateLocalSku, UUID> activateLocalSku;
    private final Handles<DefineRecipe, UUID> defineRecipe;
    private final Handles<ExecuteRepack, UUID> executeRepack;
    private final StockControlQueries control;
    private final InventoryQueries inventory;
    private final CatalogueQueries catalogue;
    private final DemoCalendar calendar;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one handler per command the demo issues; a loader, not a design
    DemoStockOperations(
            Handles<ScheduleCount, UUID> scheduleCount,
            Handles<StartCount, UUID> startCount,
            Handles<SubmitCount, UUID> submitCount,
            Handles<ApproveAdjustment, UUID> approveAdjustment,
            Handles<RequestWriteOff, UUID> requestWriteOff,
            Handles<SubmitWriteOff, UUID> submitWriteOff,
            Handles<WitnessWriteOff, UUID> witnessWriteOff,
            Handles<ApproveWriteOff, UUID> approveWriteOff,
            Handles<CreateSku, UUID> createSku,
            Handles<ActivateLocalSku, UUID> activateLocalSku,
            Handles<DefineRecipe, UUID> defineRecipe,
            Handles<ExecuteRepack, UUID> executeRepack,
            StockControlQueries control,
            InventoryQueries inventory,
            CatalogueQueries catalogue,
            DemoCalendar calendar,
            Clock clock) {
        this.scheduleCount = scheduleCount;
        this.startCount = startCount;
        this.submitCount = submitCount;
        this.approveAdjustment = approveAdjustment;
        this.requestWriteOff = requestWriteOff;
        this.submitWriteOff = submitWriteOff;
        this.witnessWriteOff = witnessWriteOff;
        this.approveWriteOff = approveWriteOff;
        this.createSku = createSku;
        this.activateLocalSku = activateLocalSku;
        this.defineRecipe = defineRecipe;
        this.executeRepack = executeRepack;
        this.control = control;
        this.inventory = inventory;
        this.catalogue = catalogue;
        this.calendar = calendar;
        this.clock = clock;
    }

    void load(Consumer<String> count) {
        LocalDate today = calendar.today();
        calendar.run(today.minusDays(20), LocalTime.of(10, 0), () -> countAtTheStores(count));
        calendar.run(today.minusDays(20), LocalTime.of(15, 0), () -> approveTheCount(count));
        calendar.run(today.minusDays(12), LocalTime.of(9, 30), () -> writeOffDamagedFlour(count));
        calendar.run(today.minusDays(12), LocalTime.of(11, 0), () -> witnessAndApprove(count));
        calendar.run(today.minusDays(6), LocalTime.of(8, 30), () -> theSocietyPack(count));
        calendar.run(today.minusDays(6), LocalTime.of(10, 0), () -> repackLooseRice(count));
    }

    // ---- the count: one lot short by one (within tolerance), one short by three (approved) ------

    private void countAtTheStores(Consumer<String> count) {
        ScopeContext buyer = scopeOf(DemoCast.M101_BUYER);
        Optional<CountView> existing = firstCount(buyer);
        if (existing.isPresent()
                && !"COUNTING".equals(existing.get().status())
                && !"SCHEDULED".equals(existing.get().status())) {
            return;
        }
        UUID taskId;
        if (existing.isEmpty()) {
            List<UUID> skus = List.of(sku(COUNTED_ITEM_SHORT_ONE, buyer), sku(COUNTED_ITEM_SHORT_THREE, buyer));
            taskId = scheduleCount.handle(
                    new ScheduleCount(DemoCast.M101_WAREHOUSE, "SKUS", skus, calendar.today()), buyer);
            count.accept("ScheduleCount");
        } else {
            taskId = existing.get().taskId();
        }
        if (!"COUNTING".equals(control.count(taskId, buyer).orElseThrow().status())) {
            startCount.handle(new StartCount(taskId), buyer);
            count.accept("StartCount");
        }
        CountView counting = control.count(taskId, buyer).orElseThrow();
        UUID shortOne = sku(COUNTED_ITEM_SHORT_ONE, buyer);
        UUID shortThree = sku(COUNTED_ITEM_SHORT_THREE, buyer);
        Map<UUID, BigDecimal> shortfall = new LinkedHashMap<>();
        shortfall.put(shortOne, BigDecimal.ONE);
        shortfall.put(shortThree, new BigDecimal("3"));
        List<SubmitCount.Line> lines = new ArrayList<>();
        for (CountView.Expected lot : counting.expectation()) {
            BigDecimal counted = lot.expectedQty();
            BigDecimal missing = "GOOD".equals(lot.condition()) ? shortfall.remove(lot.skuId()) : null;
            if (missing != null && counted.compareTo(missing) >= 0) {
                counted = counted.subtract(missing);
            }
            lines.add(new SubmitCount.Line(
                    lot.batchId(), LotCondition.valueOf(lot.condition()), counted.max(BigDecimal.ZERO), null));
        }
        submitCount.handle(new SubmitCount(taskId, lines), buyer);
        count.accept("SubmitCount");
    }

    private void approveTheCount(Consumer<String> count) {
        ScopeContext manager = scopeOf(DemoCast.M101_MANAGER);
        firstCount(manager)
                .filter(task -> "VARIANCE_REVIEW".equals(task.status()))
                .ifPresent(task -> {
                    approveAdjustment.handle(new ApproveAdjustment(task.taskId()), manager);
                    count.accept("ApproveAdjustment");
                });
    }

    /** The demo's count is the first ever at the stores; later counts are somebody's at the screen. */
    private Optional<CountView> firstCount(ScopeContext scope) {
        return control.counts(DemoCast.M101_WAREHOUSE, scope).stream()
                .min(Comparator.comparing(CountView::scheduledAt).thenComparing(CountView::taskId));
    }

    // ---- the write-off: damaged flour, witnessed by the office and approved by the manager ----

    private void writeOffDamagedFlour(Consumer<String> count) {
        ScopeContext buyer = scopeOf(DemoCast.M101_BUYER);
        Optional<WriteOffView> existing = firstWriteOff(buyer);
        UUID id;
        if (existing.isEmpty()) {
            UUID flour = sku(DAMAGED_ITEM, buyer);
            BigDecimal qty = new BigDecimal("2");
            Optional<LotBalance> lot = inventory.balances(DemoCast.M101_WAREHOUSE, flour, false, buyer).stream()
                    .filter(l -> "GOOD".equals(l.condition()) && l.qtyOnHand().compareTo(qty) >= 0)
                    .findFirst();
            if (lot.isEmpty()) {
                return;
            }
            id = requestWriteOff.handle(
                    new RequestWriteOff(
                            DemoCast.M101_WAREHOUSE,
                            LossCategory.DAMAGED_IN_STORE,
                            "Two packs torn and spoilt by a roof leak, found behind the shelf at the stock check",
                            List.of(new RequestWriteOff.Line(lot.get().batchId(), LotCondition.GOOD, qty))),
                    buyer);
            count.accept("RequestWriteOff");
        } else if ("DRAFT".equals(existing.get().status())) {
            id = existing.get().writeOffId();
        } else {
            return;
        }
        submitWriteOff.handle(new SubmitWriteOff(id), buyer);
        count.accept("SubmitWriteOff");
    }

    /**
     * The society office witnesses in person and the manager approves: since wave 2 (M5-10) the
     * approver may not be the in-person witness, so three people see a write-off where three exist.
     */
    private void witnessAndApprove(Consumer<String> count) {
        ScopeContext manager = scopeOf(DemoCast.M101_MANAGER);
        Optional<WriteOffView> writeOff = firstWriteOff(manager);
        if (writeOff.isEmpty()) {
            return;
        }
        UUID id = writeOff.get().writeOffId();
        if ("REQUESTED".equals(writeOff.get().status())) {
            witnessWriteOff.handle(new WitnessWriteOff(id), scopeOf(M101_OFFICE));
            count.accept("WitnessWriteOff");
        }
        if ("WITNESSED".equals(control.writeOff(id, manager).orElseThrow().status())) {
            approveWriteOff.handle(new ApproveWriteOff(id), manager);
            count.accept("ApproveWriteOff");
        }
    }

    private Optional<WriteOffView> firstWriteOff(ScopeContext scope) {
        return control.writeOffs(DemoCast.M101_WAREHOUSE, scope).stream()
                .min(Comparator.comparing(WriteOffView::requestedAt).thenComparing(WriteOffView::writeOffId));
    }

    // ---- the repack: loose samba rice into the society's own 5 kg packs -----------------------

    /** The society's own pack, a local item M2 knows as a repack output, and the recipe that makes it. */
    private void theSocietyPack(Consumer<String> count) {
        ScopeContext manager = scopeOf(DemoCast.M101_MANAGER);
        Optional<SkuView> pack = localSku(SOCIETY_PACK, manager);
        UUID packId;
        if (pack.isEmpty()) {
            UUID tax = catalogue.taxCategories(manager).stream()
                    .filter(t -> "EXEMPT".equals(t.code()))
                    .map(TaxCategoryView::taxCategoryId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Demo: no EXEMPT tax category"));
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("demo", true);
            packId = createSku.handle(
                    new CreateSku(new SkuDetails(
                            SOCIETY_PACK,
                            "සම්බා සහල් 5 kg, සමිති ඇසුරුම",
                            "சம்பா அரிசி 5 kg, சங்கப் பொதி",
                            null,
                            null,
                            null,
                            "EA",
                            false,
                            false,
                            false,
                            false,
                            null,
                            tax,
                            null,
                            "REPACK_OUTPUT",
                            attributes)),
                    manager);
            count.accept("CreateSku");
        } else {
            packId = pack.get().skuId();
        }
        if (!"LOCAL".equals(localSku(SOCIETY_PACK, manager).map(SkuView::status).orElse(""))) {
            activateLocalSku.handle(new ActivateLocalSku(packId), manager);
            count.accept("ActivateLocalSku");
        }
        ScopeContext buyer = scopeOf(DemoCast.M101_BUYER);
        if (recipe(buyer).isEmpty()) {
            defineRecipe.handle(
                    new DefineRecipe(RECIPE, sku(LOOSE_RICE, buyer), PACK_KG, packId, BigDecimal.ONE, BigDecimal.ZERO),
                    buyer);
            count.accept("DefineRecipe");
        }
    }

    /** Two packs from 10 kg (one from 5 kg when less is left); skipped once the stores have repacked. */
    private void repackLooseRice(Consumer<String> count) {
        ScopeContext buyer = scopeOf(DemoCast.M101_BUYER);
        Optional<RecipeView> recipe = recipe(buyer);
        if (recipe.isEmpty() || !control.repacks(DemoCast.M101_WAREHOUSE, buyer).isEmpty()) {
            return;
        }
        Optional<LotBalance> loose =
                inventory.balances(DemoCast.M101_WAREHOUSE, sku(LOOSE_RICE, buyer), false, buyer).stream()
                        .filter(l ->
                                "GOOD".equals(l.condition()) && l.qtyOnHand().compareTo(PACK_KG) >= 0)
                        .findFirst();
        if (loose.isEmpty()) {
            return;
        }
        BigDecimal packs =
                loose.get().qtyOnHand().divide(PACK_KG, 0, RoundingMode.DOWN).min(new BigDecimal("2"));
        executeRepack.handle(
                new ExecuteRepack(
                        recipe.get().recipeId(),
                        DemoCast.M101_WAREHOUSE,
                        loose.get().batchId(),
                        packs.multiply(PACK_KG),
                        packs,
                        null),
                buyer);
        count.accept("ExecuteRepack");
    }

    private Optional<RecipeView> recipe(ScopeContext scope) {
        return control.recipes(scope).stream()
                .filter(r -> RECIPE.equals(r.name()) && "ACTIVE".equals(r.status()))
                .findFirst();
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A Federation item of the demo catalogue by its English name. */
    private UUID sku(String nameEn, ScopeContext scope) {
        return catalogue.listSkus(new SkuFilter(null, nameEn, "en", 0, 50), scope).items().stream()
                .filter(s -> nameEn.equals(s.nameEn()))
                .map(SkuView::skuId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Demo: no item named " + nameEn));
    }

    /** An item of the society's own by its English name, in any state. */
    private Optional<SkuView> localSku(String nameEn, ScopeContext scope) {
        return catalogue.listSkus(new SkuFilter(null, nameEn, "en", 0, 50), scope).items().stream()
                .filter(s -> nameEn.equals(s.nameEn()) && DemoCast.M101.equals(s.ownerEntityId()))
                .findFirst();
    }

    /** As DemoDataLoader: the user's scope after signing in, with the second factor just presented. */
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
}
