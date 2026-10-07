package lk.coopfed.knoweb.m1party.internal.relationship;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.m1party.api.AnnounceCreditLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one-off publication of the opening credit limits (wave 2, CR-21A-7; the M8 decision D6):
 * every pair with an ACTIVE relationship carrying a limit that no {@code credit_limit.changed.v1}
 * has announced, which is every relationship activated before ActivateRelationship published the
 * event, gets one {@link AnnounceCreditLimit}, audited, in the seller's own scope. One
 * transaction per relationship, so one failure does not hold the others.
 *
 * <p>"One-off" by its data, not its schedule: a pair once announced has a row in
 * {@code security.credit_limit_announcement} (m1security V0019), and so does every pair activated
 * or given a new limit since, so after its first run on a database each later run finds nothing
 * and issues no command. Daily by default; the schedule is a setting like the grant expiry's.
 */
@Component
class CreditLimitBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(CreditLimitBackfillJob.class);

    /** The handler's answers that mean the pair needs nothing (any more), not a failure. */
    static final Set<String> SKIPPED = Set.of(
            "m1.relationship.credit_limit_announced",
            "m1.relationship.not_active",
            "m1.relationship.credit_limit_none");

    private final CreditLimitBackfillRows rows;
    private final Handles<AnnounceCreditLimit, UUID> announce;
    private final Clock clock;
    private final ZoneId businessZone;

    CreditLimitBackfillJob(
            CreditLimitBackfillRows rows,
            Handles<AnnounceCreditLimit, UUID> announce,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.rows = rows;
        this.announce = announce;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @ScheduledJob(
            name = "credit-limit-backfill",
            cron = "${coop-erp.m1.credit-limit-backfill-cron:0 20 3 * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int announceOpeningLimits(JobExecution execution) {
        LocalDate today = RelationshipRules.businessToday(clock, businessZone);
        List<CreditLimitBackfillRows.Due> due = rows.due(execution.federationView(), today);

        int announced = 0;
        for (CreditLimitBackfillRows.Due row : due) {
            try {
                announce.handle(
                        new AnnounceCreditLimit(row.relationshipId()), execution.ownScopeOf(row.sellerEntityId()));
                announced++;
            } catch (ProblemException overtaken) {
                if (SKIPPED.contains(overtaken.messageId())) {
                    log.debug("Relationship {} skipped: {}", row.relationshipId(), overtaken.messageId());
                } else {
                    log.error(
                            "The credit limit of relationship {} could not be announced",
                            row.relationshipId(),
                            overtaken);
                }
            } catch (RuntimeException e) {
                log.error("The credit limit of relationship {} could not be announced", row.relationshipId(), e);
            }
        }
        return announced;
    }
}
