package lk.coopfed.knoweb.m5inventory.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.web.generated.AvailabilityResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.InventoryApi;
import lk.coopfed.knoweb.m5inventory.web.generated.LotBalanceResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reads of 25A section 5 that the demo needs. The kernel checks each operation's x-permission
 * (inv.stock.view); row-level security decides which lots the caller sees. The unit cost is shown
 * to a user of the owning entity only: never to a till (25A section 9: "till-scoped balances carry
 * no cost") and never to a read-only class (section 5: "unitCost? (OWN only)").
 */
@RestController
class InventoryController implements InventoryApi {

    private final InventoryQueries queries;
    private final BatchQueries batches;
    private final CurrentScope currentScope;

    InventoryController(InventoryQueries queries, BatchQueries batches, CurrentScope currentScope) {
        this.queries = queries;
        this.batches = batches;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<LotBalanceResponse>> listBalances(UUID locationId, UUID skuId, Boolean includeZero) {
        ScopeContext scope = currentScope.get();
        boolean showCost = scope.policyClass() == PolicyClass.OWN && scope.deviceId() == null;
        Map<UUID, String> batchNumbers = new HashMap<>();

        List<LotBalanceResponse> lots =
                queries.balances(locationId, skuId, Boolean.TRUE.equals(includeZero), scope).stream()
                        .map(lot -> toResponse(lot, showCost, batchNo(batchNumbers, lot.batchId(), scope)))
                        .toList();
        return ResponseEntity.ok(lots);
    }

    /** The printed batch number, from M2, read once per batch of the answer. */
    private String batchNo(Map<UUID, String> known, UUID batchId, ScopeContext scope) {
        if (!known.containsKey(batchId)) {
            known.put(
                    batchId,
                    batches.getBatch(batchId, scope).map(BatchView::batchNo).orElse(null));
        }
        return known.get(batchId);
    }

    @Override
    public ResponseEntity<List<AvailabilityResponse>> getAvailability(List<UUID> locationIds, List<UUID> skuIds) {
        return ResponseEntity.ok(queries.availability(locationIds, skuIds, currentScope.get()).stream()
                .map(a -> new AvailabilityResponse(a.locationId(), a.skuId(), a.available()))
                .toList());
    }

    private static LotBalanceResponse toResponse(LotBalance lot, boolean showCost, String batchNo) {
        LotBalanceResponse response = new LotBalanceResponse(
                        lot.stockLotId(),
                        lot.locationId(),
                        lot.skuId(),
                        lot.batchId(),
                        LotBalanceResponse.ConditionEnum.fromValue(lot.condition()),
                        lot.qtyOnHand(),
                        lot.negative())
                .batchNo(batchNo)
                .expiryDate(lot.expiryDate())
                .fefoRank(lot.fefoRank());
        return showCost ? response.unitCost(lot.unitCost()) : response;
    }
}
