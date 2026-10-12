package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.BatchFilter;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockSold;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApplySale (25A section 6.2, receipt.issued.v1; doc 25 flow 6.2): the stock a till sold leaves
 * the shop's lots as SALE, at the entity average, citing the receipt, in the device's sequence.
 *
 * <p><b>A sale that happened is never refused</b> (AGENTS.md: apply and flag). What would be a
 * reason to refuse at a counter is a flag here:
 * <ul>
 *   <li>an <b>oversell</b> takes the lot below zero; the ledger records STOCK_LOT_NEGATIVE
 *       (REVIEW) and {@code lot.negative.v1} (doc 25 section 3.2);
 *   <li>a batch the shop never held gets its lot at the entity average; audit
 *       {@code SALE_WITHOUT_LOT} (REVIEW; 25A DR-5);
 *   <li>a line whose batch is missing or unknown to M2 takes the shop's first in-date lot of the
 *       item in FEFO order, and an expired lot only when the shop holds no in-date one (the units
 *       came from somewhere; wave 3, M1M2M3M5-16); with no lot of the item at all it posts against
 *       M2's newest in-date batch that is not another entity's repack batch (wave 2, M5-05; wave
 *       3, M1M2M3M5-22), creating the lot below zero; only an item M2 does not know is
 *       {@code SALE_LINE_UNRESOLVED} (REVIEW),
 *       for a person to settle, never dropped silently;
 *   <li>a batch past its expiry on the receipt's business date is {@code SALE_OF_EXPIRED}
 *       (REVIEW; wave 2, M5-01).
 * </ul>
 *
 * <p>Guards (the shape, not business rules): the device's OWN scope at its shop
 * ({@code m5.sale.location_required}); a document ({@code m5.ledger.document_required}). A receipt
 * whose movements exist already is not applied twice. The sale is posted at the scope's shop,
 * the one the device is enrolled at, even when the document names another (M6 flags that).
 *
 * <p>Audit {@code STOCK_SOLD}; event {@code stock.sold.v1} (and the ledger's
 * {@code stock.moved.v1} per movement). Permission: the system applies it with no user;
 * {@code inv.stock.view} is the code of the reads that show its result.
 */
@Service
@CommandHandler(permission = "inv.stock.view")
class ApplySaleHandler implements Handles<ApplySale, Integer> {

    static final String AUDIT_SOLD = "STOCK_SOLD";
    static final String AUDIT_WITHOUT_LOT = "SALE_WITHOUT_LOT";
    static final String AUDIT_UNRESOLVED = "SALE_LINE_UNRESOLVED";
    static final String AUDIT_BATCH_SUBSTITUTED = "SALE_BATCH_SUBSTITUTED";
    static final String AUDIT_LINE_SKIPPED = "SALE_LINE_SKIPPED";
    static final String AUDIT_SALE_OF_EXPIRED = "SALE_OF_EXPIRED";

    private final StockLedger ledger;
    private final ConsumerStore store;
    private final BatchQueries batches;
    private final BusinessDay businessDay;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    ApplySaleHandler(
            StockLedger ledger,
            ConsumerStore store,
            BatchQueries batches,
            BusinessDay businessDay,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.ledger = ledger;
        this.store = store;
        this.batches = batches;
        this.businessDay = businessDay;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Integer handle(ApplySale command, ScopeContext scope) {
        if (scope == null
                || scope.policyClass() != PolicyClass.OWN
                || scope.entityId() == null
                || scope.locationId() == null) {
            throw new ProblemException("m5.sale.location_required");
        }
        if (command.documentId() == null) {
            throw new ProblemException("m5.ledger.document_required");
        }
        if (store.movementsCiting(command.documentId()) > 0) {
            return 0;
        }

        UUID shop = scope.locationId();
        List<Movement> movements = new ArrayList<>();
        List<Integer> withoutLot = new ArrayList<>();
        List<Integer> unresolved = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        List<Integer> substituted = new ArrayList<>();
        List<Integer> expired = new ArrayList<>();
        LocalDate saleDate = businessDay.dateOf(command.issuedAt());
        for (ApplySale.Line line : command.lines()) {
            if (line.qty() == null || line.qty().signum() <= 0) {
                skipped.add(line.lineNo());
                continue;
            }
            Optional<UUID> batch = resolveBatch(line, shop, saleDate, scope);
            if (batch.isEmpty()) {
                unresolved.add(line.lineNo());
                continue;
            }
            if (line.batchId() != null && !line.batchId().equals(batch.get())) {
                substituted.add(line.lineNo());
            }
            if (!lotExists(shop, batch.get())) {
                withoutLot.add(line.lineNo());
            }
            if (BusinessDay.expired(expiryOf(batch.get(), scope), saleDate)) {
                expired.add(line.lineNo());
            }
            movements.add(new Movement(
                    shop,
                    batch.get(),
                    LotCondition.GOOD,
                    MovementType.SALE,
                    line.qty().negate(),
                    null,
                    line.lineId()));
        }
        if (!movements.isEmpty()) {
            ledger.post(new PostMovements(command.documentId(), command.issuedAt(), null, movements), scope);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("documentId", command.documentId());
        after.put("locationId", shop);
        after.put("movements", movements.size());
        audit.record(AUDIT_SOLD, Subject.of("document", command.documentId()), null, after, scope);
        if (!withoutLot.isEmpty()) {
            audit.record(
                    AUDIT_WITHOUT_LOT,
                    Subject.of("document", command.documentId()),
                    null,
                    Map.of("lines", withoutLot),
                    scope,
                    "Sold from a batch the shop held no lot of; the lot starts at the entity average");
        }
        if (!unresolved.isEmpty()) {
            audit.record(
                    AUDIT_UNRESOLVED,
                    Subject.of("document", command.documentId()),
                    null,
                    Map.of("lines", unresolved),
                    scope,
                    "The catalogue knows no batch of the item: the line is not posted and needs a person");
        }
        // Wave 2, M6-06 (decided 6 October 2026: docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md
        // (2)): the two traces of a sale line that was not deducted as sent. A line with no batch taking
        // the FEFO lot is the normal path (the till sends none) and stays silent; nothing restocks here
        // (a return is its own fact, receipt.refunded.v1, deferred with M6-07).
        if (!substituted.isEmpty()) {
            audit.record(
                    AUDIT_BATCH_SUBSTITUTED,
                    Subject.of("document", command.documentId()),
                    null,
                    Map.of("lines", substituted),
                    scope,
                    "The till named a batch the catalogue does not know for the item; the shop's first lot was used");
        }
        if (!skipped.isEmpty()) {
            audit.record(
                    AUDIT_LINE_SKIPPED,
                    Subject.of("document", command.documentId()),
                    null,
                    Map.of("lines", skipped),
                    scope,
                    "A sale line with no quantity, or one of zero or less, was not deducted");
        }
        // Wave 2, M5-01 (decided 6 October 2026: docs/progress/deviations/2026-10-06-wave2-expired-stock.md (3)):
        // a sale is a fact and the lot is where the units came from, so it posts; selling a batch past
        // its expiry on the receipt's own date is an offence to look into.
        if (!expired.isEmpty()) {
            audit.record(
                    AUDIT_SALE_OF_EXPIRED,
                    Subject.of("document", command.documentId()),
                    null,
                    Map.of("lines", expired, "saleDate", saleDate.toString()),
                    scope,
                    "A till sold from a batch past its expiry date");
        }
        events.publish(
                new StockSold(command.documentId(), scope.entityId(), shop, movements.size(), unresolved.size()));
        return movements.size();
    }

    /** The batch the till resolved when M2 knows it (of the line's item), else the shop's first lot of the item. */
    private Optional<UUID> resolveBatch(ApplySale.Line line, UUID shop, LocalDate saleDate, ScopeContext scope) {
        if (line.batchId() != null) {
            boolean known = batches.getBatch(line.batchId(), scope)
                    .filter(b -> line.skuId() == null || line.skuId().equals(b.skuId()))
                    .isPresent();
            if (known) {
                return Optional.of(line.batchId());
            }
        }
        if (line.skuId() == null) {
            return Optional.empty();
        }
        // In-date lots first, on the sale's own business date (wave 3, M1M2M3M5-16: an expired lot
        // waiting for its write-off is still on the books, and draining it first would write a
        // false SALE_OF_EXPIRED and overstate the in-date lot); then FEFO among the lots with
        // stock, then any lot of the item (one already oversold). An expired lot is used only when
        // the shop holds no in-date lot of the item.
        Optional<UUID> lot = jdbc
                .queryForList(
                        """
                        select batch_id from inventory.stock_lot
                         where location_id = ? and sku_id = ? and condition = 'GOOD'
                         order by (expiry_date is null or expiry_date >= ?) desc, (qty_on_hand > 0) desc,
                                  expiry_date nulls last, received_at, stock_lot_id
                         limit 1
                        """,
                        UUID.class,
                        shop,
                        line.skuId(),
                        Date.valueOf(saleDate))
                .stream()
                .findFirst();
        if (lot.isPresent()) {
            return lot;
        }
        // Wave 2, M5-05 (2026-10-06-wave2-stock-movements.md (6)): the shop never held the item, so its
        // book was nothing and the units were unrecorded stock; the sale posts against the newest
        // fitting batch, the ledger creates the lot below zero (SALE_WITHOUT_LOT, lot.negative) and
        // the negative-lots screen asks a person about it. Unresolved only for an item M2 lacks.
        return newestBatchOf(line.skuId(), saleDate, scope);
    }

    /**
     * The batch a sale of an item the shop never held posts against (wave 3, M1M2M3M5-22), the
     * first that exists of:
     * <ol>
     *   <li>the newest in-date batch M2 knows that is not superseded and is not another entity's
     *       synthetic (repack) batch;
     *   <li>as before: M2's newest batch of the item that is not superseded, then any.
     * </ol>
     * So a society's sale lands neither on another society's repack batch nor, while an in-date
     * one exists, on an expired batch (a misleading SALE_OF_EXPIRED). The review also suggested
     * preferring a batch the entity has held elsewhere; the sale runs in the device's shop scope,
     * whose row-level security shows only that shop's lots, so that step is not taken here (see
     * the deviation of this change).
     */
    private Optional<UUID> newestBatchOf(UUID skuId, LocalDate saleDate, ScopeContext scope) {
        List<BatchView> known = batches.listBatches(new BatchFilter(skuId, null, null, 10), scope);
        return known.stream()
                .filter(b -> !"SUPERSEDED".equals(b.status()))
                .filter(b -> !BusinessDay.expired(b.expiryDate(), saleDate))
                .filter(b -> !b.synthetic() || scope.entityId().equals(b.ownerEntityId()))
                .findFirst()
                .or(() -> known.stream()
                        .filter(b -> !"SUPERSEDED".equals(b.status()))
                        .findFirst())
                .or(() -> known.stream().findFirst())
                .map(BatchView::batchId);
    }

    private LocalDate expiryOf(UUID batchId, ScopeContext scope) {
        return batches.getBatch(batchId, scope).map(BatchView::expiryDate).orElse(null);
    }

    private boolean lotExists(UUID location, UUID batch) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from inventory.stock_lot where location_id = ? and batch_id = ?"
                        + " and condition = 'GOOD')",
                Boolean.class,
                location,
                batch));
    }
}
