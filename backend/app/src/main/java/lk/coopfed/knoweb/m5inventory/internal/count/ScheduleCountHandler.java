package lk.coopfed.knoweb.m5inventory.internal.count;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m5inventory.api.CountScheduled;
import lk.coopfed.knoweb.m5inventory.api.ScheduleCount;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ScheduleCount (25A section 6.3; doc 25 section 3.5, flow 6.3): a count of one location, of
 * everything or of some items, on a day.
 *
 * <p>Guards, in order: an OWN scope ({@code m5.scope.own_required}); the location one of the
 * entity's that the scope reads ({@code m5.location.not_in_scope}); the scope FULL, or SKUS with at
 * least one item the scope reads in M2 ({@code m5.count.scope_invalid}); no other count open at the
 * location ({@code m5.count.already_open}: two counts of one shelf would post one variance twice).
 * The day defaults to today.
 *
 * <p>Mutation: the task, SCHEDULED. Audit {@code COUNT_SCHEDULED}; event {@code count.scheduled.v1}.
 * Scheduling freezes nothing: the location keeps selling and receiving.
 */
@Service
@CommandHandler(permission = "inv.count.schedule")
class ScheduleCountHandler implements Handles<ScheduleCount, UUID> {

    static final String AUDIT_SCHEDULED = "COUNT_SCHEDULED";

    private final CountStore store;
    private final ControlPolicy policy;
    private final CatalogueQueries catalogue;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;
    private final AuditFacade audit;
    private final EventPublisher events;

    ScheduleCountHandler(
            CountStore store,
            ControlPolicy policy,
            CatalogueQueries catalogue,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.catalogue = catalogue;
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ScheduleCount command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        policy.requireEntityLocation(command.locationId(), scope);
        String kind = command.scopeKind() == null ? "" : command.scopeKind();
        List<UUID> skus = command.skuIds() == null ? List.of() : List.copyOf(command.skuIds());
        boolean full = "FULL".equals(kind);
        boolean bySku = "SKUS".equals(kind)
                && !skus.isEmpty()
                && skus.stream()
                        .allMatch(sku ->
                                sku != null && catalogue.getSku(sku, scope).isPresent());
        if (!full && !bySku) {
            throw new ProblemException("m5.count.scope_invalid");
        }
        if (store.openAt(command.locationId())) {
            throw new ProblemException("m5.count.already_open");
        }

        UUID id = Ids.next();
        LocalDate day =
                command.scheduledFor() == null ? LocalDate.ofInstant(clock.instant(), zone) : command.scheduledFor();
        jdbc.update(
                """
                insert into inventory.count_task
                    (task_id, owner_entity_id, location_id, scope_kind, scope_sku_ids, scheduled_for, scheduled_by,
                     scheduled_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                scope.entityId(),
                command.locationId(),
                kind,
                full ? new UUID[0] : skus.toArray(new UUID[0]),
                day,
                scope.userId(),
                Timestamp.from(clock.instant()));

        audit.record(
                AUDIT_SCHEDULED,
                Subject.of("count_task", id),
                null,
                Map.of(
                        "locationId",
                        command.locationId(),
                        "scopeKind",
                        kind,
                        "skus",
                        full ? 0 : skus.size(),
                        "scheduledFor",
                        day.toString(),
                        "status",
                        "SCHEDULED"),
                scope);
        events.publish(new CountScheduled(id, scope.entityId(), command.locationId(), kind));
        return id;
    }
}
