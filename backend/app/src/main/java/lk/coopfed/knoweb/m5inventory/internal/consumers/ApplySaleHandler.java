package lk.coopfed.knoweb.m5inventory.internal.consumers;

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
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockSold;
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
 *   <li>a line whose batch is missing or unknown to M2 takes the shop's first lot of the item in
 *       FEFO order; with no lot of the item at all it cannot be posted, and is recorded as
 *       {@code SALE_LINE_UNRESOLVED} (REVIEW) for a person to settle, never dropped silently.
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

    private final StockLedger ledger;
    private final ConsumerStore store;
    private final BatchQueries batches;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    ApplySaleHandler(
            StockLedger ledger,
            ConsumerStore store,
            BatchQueries batches,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.ledger = ledger;
        this.store = store;
        this.batches = batches;
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
        for (ApplySale.Line line : command.lines()) {
            if (line.qty() == null || line.qty().signum() <= 0) {
                skipped.add(line.lineNo());
                continue;
            }
            Optional<UUID> batch = resolveBatch(line, shop, scope);
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
                    "No batch and no lot of the item at the shop: the line is not posted and needs a person");
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
        events.publish(
                new StockSold(command.documentId(), scope.entityId(), shop, movements.size(), unresolved.size()));
        return movements.size();
    }

    /** The batch the till resolved when M2 knows it (of the line's item), else the shop's first lot of the item. */
    private Optional<UUID> resolveBatch(ApplySale.Line line, UUID shop, ScopeContext scope) {
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
        // FEFO among the lots with stock, then any lot of the item (one already oversold).
        return jdbc
                .queryForList(
                        """
                        select batch_id from inventory.stock_lot
                         where location_id = ? and sku_id = ? and condition = 'GOOD'
                         order by (qty_on_hand > 0) desc, expiry_date nulls last, received_at, stock_lot_id
                         limit 1
                        """,
                        UUID.class,
                        shop,
                        line.skuId())
                .stream()
                .findFirst();
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
