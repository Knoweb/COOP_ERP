package lk.coopfed.knoweb.m5inventory.query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The reads of 25A section 7 that other modules and the web depend on (doc 25 section 5.2). Every
 * method is read-only and takes the caller's scope: row-level security shows the caller's own
 * lots (at its location, for a shop session), everything to the Federation view, a grantee's
 * entities to an external reader.
 */
public interface InventoryQueries {

    /**
     * ListBalances: the lots of one location, optionally of one SKU; lots at zero only when asked.
     * GOOD lots with stock come first, in FEFO order, then the rest.
     */
    List<LotBalance> balances(UUID locationId, UUID skuId, boolean includeZero, ScopeContext scope);

    /**
     * Availability(locations, skus): per requested location and SKU, the GOOD lots with stock
     * less the undispatched reservations of issued delivery notes; zero where there is nothing.
     * What M4 shows buyers and allocates from (doc 24 section 6.2).
     */
    List<Availability> availability(Collection<UUID> locationIds, Collection<UUID> skuIds, ScopeContext scope);

    /**
     * PickBatches / FEFO order: the GOOD lots of the SKU with stock at the location, the one to
     * pick first first (expiry, none last, then received). M4's delivery note and M5's pick list.
     */
    List<LotBalance> pickBatches(UUID locationId, UUID skuId, ScopeContext scope);

    /** InStockBatches: the GOOD lots of the SKU with stock at any of the locations, for M3's authoring checks. */
    List<LotBalance> inStockBatches(Collection<UUID> locationIds, UUID skuId, ScopeContext scope);

    /** SkusWithLots: the SKUs with a lot other than zero at the location (M2's assortment). */
    List<UUID> skusWithLots(UUID locationId, ScopeContext scope);

    /** EntityAverageCost: the caller entity's average of the SKU, empty when it never held it. */
    Optional<EntityCost> entityAverageCost(UUID skuId, ScopeContext scope);

    /**
     * LotsConsumed(grn): whether anything but the GRN's own receipt (or its reversal) moved a lot
     * the GRN received into, since it did. M4's ReverseGrn refuses when it did (doc 24).
     */
    boolean lotsConsumed(UUID grnDocumentId, ScopeContext scope);

    /** The movements a document caused (a GRN's receipts, a delivery note's dispatch, an OPB), in ledger order. */
    List<MovementView> movementsOf(UUID documentId, ScopeContext scope);

    /**
     * The stock card of a SKU at a location (25A section 8): every movement of the SKU there, in
     * ledger order (received, source, sequence), each with the running quantity after it. The
     * running quantity is summed by the database, never by the screen.
     */
    List<StockCardLine> stockCard(UUID locationId, UUID skuId, ScopeContext scope);

    /** GetPickList(dn): the pick list of a delivery note of the caller's. */
    Optional<PickListView> pickList(UUID deliveryDocumentId, ScopeContext scope);

    /** An opening balance of the caller's with its lines. */
    Optional<OpeningBalanceView> openingBalance(UUID openingBalanceId, ScopeContext scope);

    /** A transfer of the caller's, seen from its source or its destination, with its lines. */
    Optional<TransferView> transfer(UUID transferId, ScopeContext scope);

    /** The transfers leaving or arriving at a location, newest first, with their lines. */
    List<TransferView> transfers(UUID locationId, ScopeContext scope);
}
