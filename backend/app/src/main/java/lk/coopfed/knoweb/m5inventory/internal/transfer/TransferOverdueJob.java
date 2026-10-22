package lk.coopfed.knoweb.m5inventory.internal.transfer;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Daily review of dispatched transfers that remain unreceived past the entity's threshold. */
@Component
public class TransferOverdueJob {

    public static final String AUDIT_TRANSFER_OVERDUE = "TRANSFER_OVERDUE";

    private static final Logger log = LoggerFactory.getLogger(TransferOverdueJob.class);

    private final TransferOverdueStore store;
    private final TransferOverdueReporter reporter;

    TransferOverdueJob(TransferOverdueStore store, TransferOverdueReporter reporter) {
        this.store = store;
        this.reporter = reporter;
    }

    @ScheduledJob(
            name = "inventory-transfer-overdue",
            cron = "${coop-erp.m5.transfer-overdue-cron:0 30 2 * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int review(JobExecution execution) {
        ScopeContext view = execution.federationView();
        List<TransferOverdueStore.CandidateTransfer> candidates = store.findCandidates(view);

        int reported = 0;
        for (TransferOverdueStore.CandidateTransfer candidate : candidates) {
            ScopeContext ownScope = execution.ownScopeOf(candidate.ownerEntityId());
            try {
                if (reporter.report(candidate, ownScope)) {
                    reported++;
                }
            } catch (RuntimeException e) {
                log.error("Failed to record overdue review for transfer {}", candidate.transferId(), e);
            }
        }
        execution.itemsProcessed(reported);
        return reported;
    }
}
