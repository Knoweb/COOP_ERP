package lk.coopfed.knoweb.m3pricing.internal.ceiling;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationPage;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m3pricing.api.InStockBatchQuery;
import org.springframework.stereotype.Component;

/**
 * The batches of a SKU on the shelves of an entity, with their printed MRP: M5's lots (through
 * {@link InStockBatchQuery}, which M5 answers) joined to M2's batches (the MRP is a batch
 * attribute, ADR-08). The RETAIL authoring check reads the lowest MRP at every location of the
 * society (23A section 7, SetLines); the retail price at a shop reads the batches at that shop.
 */
@Component
public class ShelfBatches {

    /** A batch with stock, and its printed MRP (null when the SKU carries none). */
    public record Shelved(UUID batchId, String batchNo, BigDecimal printedMrp, LocalDate expiry, BigDecimal onHand) {}

    private final InStockBatchQuery inStock;
    private final BatchQueries batches;
    private final PartyQueries party;

    ShelfBatches(InStockBatchQuery inStock, BatchQueries batches, PartyQueries party) {
        this.inStock = inStock;
        this.batches = batches;
        this.party = party;
    }

    /** Every location of the caller's entity (its stores and shops), as M1 lists them. */
    public List<UUID> locationsOfCaller(ScopeContext scope) {
        List<UUID> ids = new ArrayList<>();
        UUID cursor = null;
        while (true) {
            LocationPage page =
                    party.listLocations(new LocationFilter(null, null, scope.entityId(), cursor, 100), scope);
            page.items().stream().map(LocationView::locationId).forEach(ids::add);
            if (page.nextCursor() == null || page.items().isEmpty()) {
                return ids;
            }
            cursor = UUID.fromString(page.nextCursor());
        }
    }

    /** The batches of the SKU with stock at the locations, one entry per batch (quantities summed). */
    public List<Shelved> inStock(Collection<UUID> locationIds, UUID skuId, ScopeContext scope) {
        if (locationIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> onHand = new HashMap<>();
        for (InStockBatchQuery.InStockBatch lot : inStock.inStockBatches(locationIds, skuId, scope)) {
            if (lot.batchId() != null && lot.onHand() != null && lot.onHand().signum() > 0) {
                onHand.merge(lot.batchId(), lot.onHand(), BigDecimal::add);
            }
        }
        List<Shelved> shelved = new ArrayList<>();
        onHand.forEach((batchId, qty) ->
                batches.getBatch(batchId, scope).ifPresent(batch -> shelved.add(shelved(batch, qty))));
        shelved.sort(Comparator.comparing(Shelved::batchId));
        return shelved;
    }

    /** The lowest printed MRP among the batches, with the batch that carries it. */
    public static Optional<Shelved> lowestMrp(List<Shelved> shelved) {
        return shelved.stream()
                .filter(batch -> batch.printedMrp() != null)
                .min(Comparator.comparing(Shelved::printedMrp));
    }

    private static Shelved shelved(BatchView batch, BigDecimal qty) {
        return new Shelved(
                batch.batchId(),
                Objects.requireNonNullElse(batch.batchNo(), ""),
                batch.printedMrp(),
                batch.expiryDate(),
                qty);
    }
}
