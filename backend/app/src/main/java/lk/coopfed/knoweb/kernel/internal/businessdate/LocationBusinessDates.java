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
 * <p>A location gets its row when it is registered ({@link LocationRegisteredListener}) or,
 * for one registered before that listener existed, from the cut-off job; until then it trades
 * on the calendar date in the business time zone and its first close writes the row. From
 * then on the date moves only through {@link #close}: to the day after the one closed, or to
 * today when the location was shut for longer (a shop closed for a week does not reopen a
 * week behind).
 *
 * <p>A close locks the row and moves the date only when it is still the one it read, so two
 * closes of the same day (the trigger and the cut-off, or a redelivered event on another
 * instance) make one change, one audit record and one event. A close of a day already closed
 * (the business date is past today) changes nothing.
 */
@Component
public class LocationBusinessDates implements BusinessDate, DayClose {

    static final String AUDIT_DAY_CLOSED = "DAY_CLOSED";

    private static final String SELECT_STATE =
            "select location_id, owner_entity_id, business_date, closed_at from kernel.location_business_date";

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
        return find(locationId, false).map(State::businessDate).orElseGet(this::today);
    }

    @Override
    public LocalDate currentHeld(UUID locationId) {
        Objects.requireNonNull(locationId, "a business date belongs to a location");

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("BusinessDate.currentHeld was called outside a transaction");
        }

        // FOR SHARE: a day close (FOR UPDATE) waits until this transaction ends, so a document
        // committing while the day closes carries the date it was issued on, not the next one.
        return find(locationId, true).map(State::businessDate).orElseGet(this::today);
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

        // A location without a row (registered before the rows were created at registration)
        // gets one now, on the calendar date; a concurrent first close inserts nothing and
        // reads the row below. Under row-level security the insert only ever writes the
        // caller's own entity, and a row of another entity is not seen afterwards.
        register(locationId, ctx.entityId(), today);

        State state = lockedFind(locationId).orElseThrow(() -> new ProblemException("location.not_in_scope"));

        // Already closed today: the business date moved past the calendar date. Keyed on the
        // business date, not on when the last close ran, so a cut-off after midnight does not
        // swallow that evening's real close (review of 26 Sep).
        if (state.businessDate().isAfter(today)) {
            return state.businessDate();
        }

        LocalDate closed = state.businessDate();
        LocalDate next = closed.plusDays(1).isAfter(today) ? closed.plusDays(1) : today;

        // Conditional on the date read under the lock: exactly one close moves it.
        int updated = jdbc.update(
                "update kernel.location_business_date set business_date = ?, closed_at = ?"
                        + " where location_id = ? and business_date = ?",
                next,
                Timestamp.from(now),
                locationId,
                closed);

        if (updated == 0) {
            throw new ProblemException("location.not_in_scope");
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

    /** Gives a location its row on today's calendar date; nothing happens when it has one. */
    void register(UUID locationId, UUID ownerEntityId) {
        register(locationId, ownerEntityId, today());
    }

    private void register(UUID locationId, UUID ownerEntityId, LocalDate businessDate) {
        jdbc.update(
                "insert into kernel.location_business_date (location_id, owner_entity_id, business_date)"
                        + " values (?, ?, ?) on conflict (location_id) do nothing",
                locationId,
                ownerEntityId,
                businessDate);
    }

    /** Every location whose business date is behind today, under the caller's scope: what the cut-off closes. */
    List<State> behindToday() {
        return jdbc.query(
                SELECT_STATE + " where business_date < ? order by owner_entity_id, location_id",
                (rs, rowNum) -> row(rs),
                today());
    }

    /**
     * Every location of M1's register without a row here, under the caller's scope (the cut-off
     * reads as the federation viewer). The kernel reads {@code party.location} the way the
     * scope filter's {@code LocationOwners} does: the set of locations is M1's, and a location
     * that never closed a day would otherwise never be cut off (review of 26 Sep).
     */
    List<State> withoutARow() {
        return jdbc.query(
                "select l.location_id, l.owner_entity_id, null::date as business_date, null::timestamptz as closed_at"
                        + " from party.location l"
                        + " where not exists (select 1 from kernel.location_business_date d"
                        + " where d.location_id = l.location_id)"
                        + " order by l.owner_entity_id, l.location_id",
                (rs, rowNum) -> row(rs));
    }

    private Optional<State> find(UUID locationId, boolean held) {
        return jdbc
                .query(
                        SELECT_STATE + " where location_id = ?" + (held ? " for share" : ""),
                        (rs, rowNum) -> row(rs),
                        locationId)
                .stream()
                .findFirst();
    }

    /** The row locked for this transaction: a second close, and any issuance holding the date, waits. */
    private Optional<State> lockedFind(UUID locationId) {
        return jdbc
                .query(SELECT_STATE + " where location_id = ? for update", (rs, rowNum) -> row(rs), locationId)
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
