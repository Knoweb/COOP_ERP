package lk.coopfed.knoweb.m5inventory.internal.review;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.LotNegativeEscalated;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional reporter for escalating unacknowledged negative stock lots (doc 25 section 3.2; 25A section 7.2; ticket M5-06b / INV-04).
 *
 * <p>Each escalation runs in its own transaction ({@code REQUIRES_NEW}) in the OWN scope of the lot's owner entity.
 * Stale candidates are revalidated before recording. Duplicates are prevented via {@link AuditReviewReader#reviewExists}.
 */
@Component
public class NegativeLotEscalationReporter {

    private static final Logger log = LoggerFactory.getLogger(NegativeLotEscalationReporter.class);

    private final NegativeLotReviewStore store;
    private final AuditReviewReader reviews;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final BusinessDay businessDay;
    private final ControlPolicy policy;

    public NegativeLotEscalationReporter(
            NegativeLotReviewStore store,
            AuditReviewReader reviews,
            AuditFacade audit,
            EventPublisher events,
            BusinessDay businessDay,
            ControlPolicy policy) {
        this.store = store;
        this.reviews = reviews;
        this.audit = audit;
        this.events = events;
        this.businessDay = businessDay;
        this.policy = policy;
    }

    /**
     * Report and escalate one negative stock lot under its owner's OWN scope.
     *
     * @param candidate candidate lot found during discovery
     * @param thresholdDays required days negative before escalating
     * @param scope owner's OWN scope ({@code execution.ownScopeOf(candidate.ownerEntityId())})
     * @return true if a new escalation review was recorded, false if already escalated or no longer eligible
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reportEscalation(
            NegativeLotReviewStore.CandidateLot candidate, int thresholdDays, ScopeContext scope) {

        // 1. Revalidate under OWN scope to ensure candidate is not stale (has not been acknowledged or replenished)
        Optional<NegativeLotReviewStore.CandidateLot> currentOpt = store.findLotById(candidate.stockLotId(), scope);
        if (currentOpt.isEmpty()) {
            log.debug(
                    "Lot {} is no longer negative or has been acknowledged; skipping escalation",
                    candidate.stockLotId());
            return false;
        }

        NegativeLotReviewStore.CandidateLot lot = currentOpt.get();

        // 2. Verify that negative_since matches the candidate (has not cleared and restarted with a newer time)
        if (!lot.negativeSince().equals(candidate.negativeSince())) {
            log.debug("Lot {} negative_since changed; skipping stale candidate", lot.stockLotId());
            return false;
        }

        // 3. Compute elapsed business days in Colombo timezone and evaluate effective threshold under OWN scope
        int effectiveThreshold = (policy != null) ? policy.negativeLotEscalationDays(scope) : thresholdDays;
        if (effectiveThreshold <= 0) {
            effectiveThreshold = thresholdDays;
        }

        LocalDate negativeDate = businessDay.dateOf(lot.negativeSince());
        LocalDate today = businessDay.today();
        long daysNegative = ChronoUnit.DAYS.between(negativeDate, today);

        if (daysNegative < effectiveThreshold) {
            log.debug(
                    "Lot {} has been negative for {} days, threshold is {}; skipping",
                    lot.stockLotId(),
                    daysNegative,
                    effectiveThreshold);
            return false;
        }

        // 4. Check whether an unresolved escalation review was already recorded for this negative period
        boolean alreadyReported = reviews.reviewExists(
                NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED,
                lot.stockLotId(),
                "negativeSince",
                lot.negativeSince().toString(),
                scope);

        if (alreadyReported) {
            log.debug(
                    "Lot {} has already been escalated for period since {}; skipping duplicate",
                    lot.stockLotId(),
                    lot.negativeSince());
            return false;
        }

        // 5. Record the audited review
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stockLotId", lot.stockLotId().toString());
        details.put("locationId", lot.locationId().toString());
        details.put("batchId", lot.batchId().toString());
        details.put("skuId", lot.skuId().toString());
        details.put("condition", lot.condition());
        details.put("qtyOnHand", lot.qtyOnHand().toPlainString());
        details.put("negativeSince", lot.negativeSince().toString());
        details.put("daysNegative", (int) daysNegative);
        details.put("thresholdDays", effectiveThreshold);

        audit.record(
                NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED,
                Subject.of("stock_lot", lot.stockLotId()),
                null,
                details,
                scope,
                "Oversold stock lot remained unacknowledged for " + daysNegative + " days");

        // 6. Publish domain event
        events.publish(new LotNegativeEscalated(
                lot.stockLotId(),
                lot.ownerEntityId(),
                lot.locationId(),
                lot.batchId(),
                lot.skuId(),
                lot.qtyOnHand(),
                lot.negativeSince(),
                (int) daysNegative));

        log.warn(
                "Escalated unacknowledged negative lot {} at location {}: qtyOnHand={}, daysNegative={}",
                lot.stockLotId(),
                lot.locationId(),
                lot.qtyOnHand(),
                daysNegative);

        return true;
    }
}
