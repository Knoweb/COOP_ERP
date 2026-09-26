package lk.coopfed.knoweb.m1party.internal.grant;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.ExpireExternalGrant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * "Expiry by clock" (doc 21 flow 6.5 and section 4.6): every grant still ACTIVE whose
 * {@code valid_until} has passed is marked EXPIRED, audited and announced, one transaction per
 * grant so that one failure does not hold the others.
 *
 * <p>Access does not wait for this job: the kernel's resolution ({@code JdbcUserScopes.grantsOf})
 * checks the window itself and keeps no entry past the earliest end, so an ended grant admits
 * nothing from the second it ends. The job keeps the
 * register honest (a grant listed ACTIVE that admits nobody would mislead the Federation) and
 * leaves the audit record and the event the flow asks for. Hourly by default; the schedule is a
 * setting like the day-close cut-off's.
 *
 * <p>It works in the Federation's system scope ({@code coop-erp.system.entity-id}); without one it
 * logs and changes nothing, as 19A section 12 says of every job that must audit.
 */
@Component
class ExternalGrantExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExternalGrantExpiryJob.class);

    /** The handler's answers that mean the grant is no longer one to expire, not a failure. */
    static final Set<String> SKIPPED = Set.of("m1.grant.not_active", "m1.grant.not_due");

    private final ExternalGrantRows rows;
    private final Handles<ExpireExternalGrant, UUID> expire;
    private final Clock clock;

    ExternalGrantExpiryJob(ExternalGrantRows rows, Handles<ExpireExternalGrant, UUID> expire, Clock clock) {
        this.rows = rows;
        this.expire = expire;
        this.clock = clock;
    }

    @ScheduledJob(
            name = "external-grant-expiry",
            cron = "${coop-erp.m1.external-grant-expiry-cron:0 5 * * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int expireEndedGrants(JobExecution execution) {
        Optional<ScopeContext> system = execution.systemScope();

        if (system.isEmpty()) {
            log.warn("External grant expiry skipped: coop-erp.system.entity-id is not set, so nothing can be audited");
            return 0;
        }

        ScopeContext scope = system.get();
        List<UUID> due = rows.dueForExpiry(scope, clock.instant());

        int expired = 0;
        for (UUID grantId : due) {
            try {
                expire.handle(new ExpireExternalGrant(grantId), scope);
                expired++;
            } catch (ProblemException overtaken) {
                if (SKIPPED.contains(overtaken.messageId())) {
                    // A revocation, or another instance's run, got there first: nothing to do.
                    log.debug("External grant {} skipped: {}", grantId, overtaken.messageId());
                } else {
                    log.error("External grant {} could not be marked expired", grantId, overtaken);
                }
            } catch (RuntimeException e) {
                log.error("External grant {} could not be marked expired", grantId, e);
            }
        }
        return expired;
    }
}
