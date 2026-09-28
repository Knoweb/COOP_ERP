package lk.coopfed.knoweb.m3pricing.internal.ceiling;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceRescinded;
import lk.coopfed.knoweb.m3pricing.api.RescindControlPrice;
import lk.coopfed.knoweb.m3pricing.internal.list.Federation;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import lk.coopfed.knoweb.m3pricing.query.ControlPriceView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RescindControlPrice (23A section 7; doc 23 section 4.3). Guards: the Federation in an
 * entity-wide OWN scope (second factor by the permission); the control price exists; a reason and
 * the rescinding gazette's reference; the last day is within the ceiling's range (not before its
 * first day, not after its current end) and not before yesterday (a ceiling is law on the days it
 * held; it is ended, never undone). Mutation: effective_to = the last day. The reason and the
 * reference are kept in the audit record (23A's table has no column for them).
 */
@Service
@CommandHandler(permission = "prc.controlprice.enter", requiresMfa = true)
public class RescindControlPriceHandler implements Handles<RescindControlPrice, UUID> {

    static final String AUDIT_RESCINDED = "CONTROL_PRICE_RESCINDED";

    private final JdbcTemplate jdbc;
    private final ControlPriceStore store;
    private final Federation federation;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    RescindControlPriceHandler(
            JdbcTemplate jdbc,
            ControlPriceStore store,
            Federation federation,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jdbc = jdbc;
        this.store = store;
        this.federation = federation;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Override
    @Transactional
    public UUID handle(RescindControlPrice command, ScopeContext scope) {
        if (command == null || command.controlPriceId() == null || command.lastDay() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        if (!federation.isCaller(scope)) {
            throw new ProblemException("m3.control_price.federation_only");
        }
        ControlPriceView row = store.find(command.controlPriceId())
                .orElseThrow(() -> new ProblemException("m3.control_price.not_found"));
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "reason"));
        }
        if (command.gazetteReference() == null || command.gazetteReference().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "gazetteReference"));
        }
        LocalDate lastDay = command.lastDay();
        if (lastDay.isBefore(row.effectiveFrom())
                || (row.effectiveTo() != null && !lastDay.isBefore(row.effectiveTo()))) {
            throw new ProblemException("m3.control_price.last_day_invalid");
        }
        LocalDate today = PriceListRules.today(clock, businessZone);
        if (lastDay.isBefore(today.minusDays(1))) {
            throw new ProblemException("m3.control_price.last_day_past");
        }

        jdbc.update(
                "update pricing.control_price set effective_to = ? where control_price_id = ?",
                Date.valueOf(lastDay),
                row.controlPriceId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("effectiveTo", row.effectiveTo());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("effectiveTo", lastDay);
        after.put("reason", command.reason().strip());
        after.put("gazetteReference", command.gazetteReference().strip());
        audit.record(AUDIT_RESCINDED, Subject.of("control_price", row.controlPriceId()), before, after, scope);

        events.publish(new ControlPriceRescinded(
                row.controlPriceId(),
                row.skuId(),
                lastDay,
                command.gazetteReference().strip()));
        return row.controlPriceId();
    }
}
