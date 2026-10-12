package lk.coopfed.knoweb.m3pricing.internal.review;

import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistered;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceEntered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gazette review and the batch-MRP review of doc 23 section 4.3 (23A section 7.1), first
 * half. Each consumer runs in the scope the dispatcher gives it, the event owner's OWN scope, and
 * only records the event in pricing.review_trigger, in that owner's name (a replay records
 * nothing: one row per batch, one per control price). It takes no other entity's scope: which
 * societies' lists the event breaches is read, and each review raised in its owner's name, by
 * {@link PricingReviewJob}, a @ScheduledJob (CR-19A-7; M3R-08, CR-19A-14).
 */
@Component
public class ReviewConsumers {

    private final JdbcTemplate jdbc;

    ReviewConsumers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    @EventConsumer(types = BatchRegistered.TYPE, consumer = "m3.batch-mrp-review")
    public void onBatchRegistered(BatchRegistered event, ScopeContext scope) {
        if (event.printedMrp() == null) {
            return;
        }
        // No unit: a printed MRP is the price of the SKU's base unit, which the job reads.
        jdbc.update(
                "insert into pricing.review_trigger (trigger_id, owner_entity_id, kind, sku_id, source_id,"
                        + " source_ref, threshold_price, uom_code) values (?, ?, ?, ?, ?, ?, ?, null)"
                        + " on conflict (kind, source_id) do nothing",
                Ids.next(),
                scope.entityId(),
                ReviewTriggers.BATCH_MRP,
                event.skuId(),
                event.batchId(),
                event.batchNo(),
                event.printedMrp());
    }

    @Transactional
    @EventConsumer(types = ControlPriceEntered.TYPE, consumer = "m3.gazette-review")
    public void onControlPriceEntered(ControlPriceEntered event, ScopeContext scope) {
        jdbc.update(
                "insert into pricing.review_trigger (trigger_id, owner_entity_id, kind, sku_id, source_id,"
                        + " source_ref, threshold_price, uom_code) values (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " on conflict (kind, source_id) do nothing",
                Ids.next(),
                scope.entityId(),
                ReviewTriggers.GAZETTE,
                event.skuId(),
                event.controlPriceId(),
                event.gazetteReference(),
                event.ceilingPrice(),
                event.ceilingUomCode());
    }
}
