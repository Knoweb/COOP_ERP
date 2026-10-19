package lk.coopfed.knoweb.m3pricing.internal.rule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.ExpireRule;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The rule expiry job of 23A section 7 (doc 23 section 4.2; doc 03 section 12).
 * Every run: finds ACTIVE rules whose valid_to date has passed (valid_to &lt; today),
 * read with the federation-wide view (CR-19A-7), and transitions them to EXPIRED
 * in each rule owner's OWN scope.
 */
@Component
public class RuleExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(RuleExpiryJob.class);

    private final RuleStore store;
    private final ExpireRuleHandler expire;
    private final Clock clock;
    private final ZoneId businessZone;

    RuleExpiryJob(
            RuleStore store,
            ExpireRuleHandler expire,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.store = store;
        this.expire = expire;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @ScheduledJob(
            name = "rule-expiry",
            cron = "${coop-erp.m3.rule-expiry-cron:0 5 0 * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int expireRules(JobExecution execution) {
        ScopeContext view = execution.federationView();
        LocalDate today = PriceListRules.today(clock, businessZone);
        List<RuleStore.DueRule> due = store.dueForExpiry(today, view);

        int expired = 0;
        for (RuleStore.DueRule rule : due) {
            try {
                expire.handle(new ExpireRule(rule.ruleId()), execution.ownScopeOf(rule.ownerEntityId()));
                expired++;
            } catch (ProblemException overtaken) {
                if ("m3.rule.not_active".equals(overtaken.messageId())
                        || "m3.rule.not_due".equals(overtaken.messageId())
                        || "m3.rule.not_found".equals(overtaken.messageId())) {
                    log.debug("Rule {} skipped: {}", rule.ruleId(), overtaken.messageId());
                } else {
                    log.error("Rule {} could not be expired", rule.ruleId(), overtaken);
                }
            } catch (RuntimeException e) {
                log.error("Rule {} could not be expired", rule.ruleId(), e);
            }
        }
        return expired;
    }
}
