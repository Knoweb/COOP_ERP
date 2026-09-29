package lk.coopfed.knoweb.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
import lk.coopfed.knoweb.m4trading.api.ApproveClaim;
import lk.coopfed.knoweb.m4trading.api.ApproveTransferRequest;
import lk.coopfed.knoweb.m4trading.api.RaiseClaim;
import lk.coopfed.knoweb.m4trading.api.RequestTransfer;
import lk.coopfed.knoweb.m4trading.query.ClaimQueries;
import lk.coopfed.knoweb.m4trading.query.ClaimView;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
import lk.coopfed.knoweb.m4trading.query.GrnView;
import lk.coopfed.knoweb.m4trading.query.InvoiceBalance;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.TransferRequestQueries;
import lk.coopfed.knoweb.m4trading.query.TransferRequestView;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * M4-06 and M4-10 in the demo: a claim and a transfer request.
 *
 * <ul>
 *   <li><b>The claim.</b> Two days after Wayamba Distributors (D101) confirmed the latest goods
 *       received note of the Federation's that it still owes money on, its buyer (d101-buyer)
 *       claims two units of the first line as damaged; the next day the Federation's accounts
 *       (fed-accounts) approve the claim and the credit note is issued with the approval.
 *   <li><b>The transfer request.</b> This morning the town shop's staff (m101-shop) asked the
 *       society for two items the stores hold; the society manager (m101-manager) approved it
 *       from the stores, so the
 *       stores' transfer follows (M5 issues it on the approval) and the shop receives it on the
 *       Transfers screen.
 * </ul>
 *
 * <p>Idempotent: the claim is found by its GRN among D101's claims, the request by its reason
 * among the town shop's requests; each step is taken only while it is missing.
 */
@Service
class DemoClaimsAndTransfers {

    static final String CLAIM_NOTE = "Two cartons crushed, found when the pallet was unpacked";
    static final String REQUEST_REASON = "Weekend stock for the town shop";

    private static final BigDecimal CLAIMED = new BigDecimal("2");
    private static final BigDecimal REQUESTED = new BigDecimal("6");

    private final Handles<RaiseClaim, UUID> raiseClaim;
    private final Handles<ApproveClaim, UUID> approveClaim;
    private final Handles<RequestTransfer, UUID> requestTransfer;
    private final Handles<ApproveTransferRequest, Void> approveRequest;
    private final InvoiceQueries invoices;
    private final GrnQueries grns;
    private final ClaimQueries claims;
    private final TransferRequestQueries requests;
    private final InventoryQueries inventory;
    private final DemoCalendar calendar;
    private final Clock clock;
    private final ZoneId zone;

    @SuppressWarnings("java:S107") // one handler per command the demo issues; a loader, not a design
    DemoClaimsAndTransfers(
            Handles<RaiseClaim, UUID> raiseClaim,
            Handles<ApproveClaim, UUID> approveClaim,
            Handles<RequestTransfer, UUID> requestTransfer,
            Handles<ApproveTransferRequest, Void> approveRequest,
            InvoiceQueries invoices,
            GrnQueries grns,
            ClaimQueries claims,
            TransferRequestQueries requests,
            InventoryQueries inventory,
            DemoCalendar calendar,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone) {
        this.raiseClaim = raiseClaim;
        this.approveClaim = approveClaim;
        this.requestTransfer = requestTransfer;
        this.approveRequest = approveRequest;
        this.invoices = invoices;
        this.grns = grns;
        this.claims = claims;
        this.requests = requests;
        this.inventory = inventory;
        this.calendar = calendar;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    void load(Consumer<String> count) {
        claim(count);
        transferRequest(count);
    }

    private void claim(Consumer<String> count) {
        ScopeContext buyer = scopeOf(DemoCast.D101_BUYER);
        ScopeContext accounts = scopeOf(DemoCast.FED_ACCOUNTS);
        // The latest of the Federation's invoices to D101 that still has money due.
        Optional<InvoiceView> invoice = invoices.listInvoices(OrderQueries.Role.SELLER, accounts).stream()
                .filter(view -> DemoCast.D101.equals(view.buyerEntityId()))
                .filter(view -> invoices.balance(view.invoiceId(), accounts)
                                .map(InvoiceBalance::amountDue)
                                .orElse(BigDecimal.ZERO)
                                .signum()
                        > 0)
                .max(Comparator.comparing(InvoiceView::taxPointDate).thenComparing(InvoiceView::docNumberDisplay));
        if (invoice.isEmpty() || invoice.get().grnIds().isEmpty()) {
            return;
        }
        UUID grnId = invoice.get().grnIds().get(0);
        Optional<ClaimView> existing = claims.listClaims(OrderQueries.Role.BUYER, buyer).stream()
                .filter(view -> grnId.equals(view.grnId()))
                .findFirst();
        LocalDate today = calendar.today();
        UUID claimId;
        LocalDate raisedOn;
        if (existing.isPresent()) {
            claimId = existing.get().claimId();
            raisedOn = LocalDate.ofInstant(existing.get().raisedAt(), zone);
        } else {
            Optional<GrnView> grn = grns.getGrn(grnId, buyer);
            Optional<GrnView.GrnLineView> line = grn.stream()
                    .flatMap(view -> view.lines().stream())
                    .filter(candidate -> candidate.receivedQty() != null
                            && candidate.receivedQty().compareTo(CLAIMED) >= 0)
                    .findFirst();
            if (grn.isEmpty() || grn.get().confirmedAt() == null || line.isEmpty()) {
                return;
            }
            raisedOn = min(LocalDate.ofInstant(grn.get().confirmedAt(), zone).plusDays(2), today);
            UUID grnLineId = line.get().lineId();
            claimId = calendar.at(
                    raisedOn,
                    LocalTime.of(10, 30),
                    () -> raiseClaim.handle(
                            new RaiseClaim(
                                    grnId,
                                    "DAMAGED",
                                    false,
                                    CLAIM_NOTE,
                                    List.of(new RaiseClaim.Line(grnLineId, CLAIMED))),
                            buyer));
            count.accept("RaiseClaim");
        }
        boolean undecided = claims.getClaim(claimId, accounts)
                .map(view -> ClaimView.RAISED.equals(view.status()))
                .orElse(false);
        if (undecided) {
            calendar.run(
                    min(raisedOn.plusDays(1), today),
                    LocalTime.of(11, 0),
                    () -> approveClaim.handle(
                            new ApproveClaim(claimId, "Crushed cartons seen on the photographs", false, List.of()),
                            accounts));
            count.accept("ApproveClaim");
        }
    }

    private void transferRequest(Consumer<String> count) {
        ScopeContext shop = scopeOf(DemoCast.M101_SHOP_STAFF);
        ScopeContext manager = scopeOf(DemoCast.M101_MANAGER);
        Optional<TransferRequestView> existing = requests.listRequests(shop).stream()
                .filter(view -> REQUEST_REASON.equals(view.reason()))
                .findFirst();
        UUID requestId;
        LocalDate today = calendar.today();
        if (existing.isPresent()) {
            requestId = existing.get().requestId();
        } else {
            Map<UUID, BigDecimal> held = new LinkedHashMap<>();
            for (LotBalance lot : inventory.balances(DemoCast.M101_WAREHOUSE, null, false, manager)) {
                if ("GOOD".equals(lot.condition()) && lot.qtyOnHand().signum() > 0) {
                    held.merge(lot.skuId(), lot.qtyOnHand(), BigDecimal::add);
                }
            }
            // The two items the stores hold most of (the same two on every run), half of it at most.
            List<RequestTransfer.Line> lines = held.entrySet().stream()
                    .filter(entry -> entry.getValue().compareTo(BigDecimal.TWO) >= 0)
                    .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue()
                            .reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(2)
                    .map(entry -> new RequestTransfer.Line(
                            entry.getKey(),
                            REQUESTED.min(entry.getValue().divide(BigDecimal.TWO, 0, java.math.RoundingMode.DOWN))))
                    .toList();
            if (lines.isEmpty()) {
                return;
            }
            requestId = calendar.at(
                    today,
                    LocalTime.of(8, 15),
                    () -> requestTransfer.handle(
                            new RequestTransfer(null, DemoCast.M101_TOWN_SHOP, REQUEST_REASON, lines), shop));
            count.accept("RequestTransfer");
        }
        boolean undecided = requests.getRequest(requestId, manager)
                .map(view -> "REQUESTED".equals(view.status()))
                .orElse(false);
        if (undecided) {
            calendar.run(
                    today,
                    LocalTime.of(8, 45),
                    () -> approveRequest.handle(
                            new ApproveTransferRequest(requestId, DemoCast.M101_WAREHOUSE), manager));
            count.accept("ApproveTransferRequest");
        }
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? b : a;
    }

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
