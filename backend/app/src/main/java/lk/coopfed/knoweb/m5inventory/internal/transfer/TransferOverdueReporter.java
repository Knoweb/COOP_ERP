package lk.coopfed.knoweb.m5inventory.internal.transfer;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records one overdue-transfer review in the transfer owner's OWN scope. */
@Component
public class TransferOverdueReporter {

    private static final Logger log = LoggerFactory.getLogger(TransferOverdueReporter.class);

    private final TransferOverdueStore store;
    private final AuditReviewReader reviews;
    private final AuditFacade audit;
    private final BusinessDay businessDay;
    private final ControlPolicy policy;

    public TransferOverdueReporter(
            TransferOverdueStore store,
            AuditReviewReader reviews,
            AuditFacade audit,
            BusinessDay businessDay,
            ControlPolicy policy) {
        this.store = store;
        this.reviews = reviews;
        this.audit = audit;
        this.businessDay = businessDay;
        this.policy = policy;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean report(TransferOverdueStore.CandidateTransfer candidate, ScopeContext scope) {
        Optional<TransferOverdueStore.CandidateTransfer> current = store.findById(candidate.transferId(), scope);
        if (current.isEmpty() || !current.get().dispatchedAt().equals(candidate.dispatchedAt())) {
            log.debug(
                    "Transfer {} was received or its dispatch changed; skipping stale candidate",
                    candidate.transferId());
            return false;
        }

        TransferOverdueStore.CandidateTransfer transfer = current.get();
        int thresholdDays = policy.transferReceiptDays(scope);
        LocalDate dispatchDate = businessDay.dateOf(transfer.dispatchedAt());
        long elapsedDays = ChronoUnit.DAYS.between(dispatchDate, businessDay.today());
        if (elapsedDays < thresholdDays) {
            return false;
        }

        if (reviews.reviewExists(
                TransferOverdueJob.AUDIT_TRANSFER_OVERDUE,
                transfer.transferId(),
                "dispatchedAt",
                transfer.dispatchedAt().toString(),
                scope)) {
            return false;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("transferId", transfer.transferId().toString());
        details.put("ownerEntityId", transfer.ownerEntityId().toString());
        details.put("fromLocationId", transfer.fromLocationId().toString());
        details.put("toLocationId", transfer.toLocationId().toString());
        details.put("dispatchedAt", transfer.dispatchedAt().toString());
        details.put("elapsedDays", (int) elapsedDays);
        details.put("thresholdDays", thresholdDays);

        audit.record(
                TransferOverdueJob.AUDIT_TRANSFER_OVERDUE,
                Subject.of("transfer", transfer.transferId()),
                null,
                details,
                scope,
                "Dispatched transfer remained unreceived for " + elapsedDays + " calendar days");
        return true;
    }
}
