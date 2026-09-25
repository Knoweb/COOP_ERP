package lk.coopfed.knoweb.kernel.internal.businessdate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.BusinessDate;
import lk.coopfed.knoweb.kernel.api.DayClose;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.LocationDayClosed;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The business date of a location, from its day-close state in
 * {@code kernel.location_business_date} (19A section 13, K-13; doc 19 section 9). Replaces
 * the 17A stub that answered the calendar date for every location.
 *
 * <p>A location that has never closed a day has no row and trades on the calendar date in the
 * business time zone; its first close writes the row. From then on the date moves only
 * through {@link #close}: to the day after the one closed, or to today when the location
 * was shut for longer (a shop closed for a week does not reopen a week behind).
 */
@Component
public class LocationBusinessDates implements BusinessDate, DayClose {

    static final String AUDIT_DAY_CLOSED = "DAY_CLOSED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    public LocationBusinessDates(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    @Override
    public LocalDate current(UUID locationId) {
        Objects.requireNonNull(locationId, "a business date belongs to a location");
        return find(locationId).map(State::businessDate).orElseGet(this::today);
    }

    @Override
    public LocalDate close(UUID locationId, ScopeContext ctx) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("DayClose.close was called outside a transaction");
        }

        if (ctx == null || ctx.entityId() == null) {
            throw new ProblemException("scope.required");
        }

        Instant now = clock.instant();
        LocalDate today = today();
        Optional<State> state = find(locationId);

        // Once per calendar day at most: a second close today changes nothing.
        if (state.isPresent()
                && state.get().closedAt() != null
                && LocalDate.ofInstant(state.get().closedAt(), zone).equals(today)) {
            return state.get().businessDate();
        }

        LocalDate closed = state.map(State::businessDate).orElse(today);
        LocalDate next = closed.plusDays(1).isAfter(today) ? closed.plusDays(1) : today;

        if (state.isEmpty()) {
            jdbc.update(
                    "insert into kernel.location_business_date (location_id, owner_entity_id, business_date, closed_at)"
                            + " values (?, ?, ?, ?)",
                    locationId,
                    ctx.entityId(),
                    next,
                    Timestamp.from(now));
        } else {
            int updated = jdbc.update(
                    "update kernel.location_business_date set business_date = ?, closed_at = ? where location_id = ?",
                    next,
                    Timestamp.from(now),
                    locationId);
            if (updated == 0) {
                throw new ProblemException("location.not_in_scope");
            }
        }

        audit.record(
                AUDIT_DAY_CLOSED,
                Subject.of("location", locationId),
                Map.of("businessDate", closed.toString()),
                Map.of("businessDate", next.toString()),
                ctx);

        events.publish(new LocationDayClosed(locationId, closed, next, ctx.entityId()));

        return next;
    }

    /** Every location whose business date is behind today, under the caller's scope: what the cut-off closes. */
    List<State> behindToday() {
        return jdbc.query(
                "select location_id, owner_entity_id, business_date, closed_at from kernel.location_business_date"
                        + " where business_date < ? order by owner_entity_id, location_id",
                (rs, rowNum) -> row(rs),
                today());
    }

    private Optional<State> find(UUID locationId) {
        return jdbc
                .query(
                        "select location_id, owner_entity_id, business_date, closed_at"
                                + " from kernel.location_business_date where location_id = ?",
                        (rs, rowNum) -> row(rs),
                        locationId)
                .stream()
                .findFirst();
    }

    private static State row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new State(
                rs.getObject("location_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("business_date", LocalDate.class),
                closedAt == null ? null : closedAt.toInstant());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    record State(UUID locationId, UUID ownerEntityId, LocalDate businessDate, Instant closedAt) {}
}
