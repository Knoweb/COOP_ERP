package lk.coopfed.knoweb.kernel.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The counters of the document base (doc 18 section 5.5; 19A section 7; ADR-11; 24B). One
 * series is one row of {@code kernel.numbering_series} with one writer: at issuance the number
 * is taken by a single-row {@code UPDATE ... RETURNING} inside the issuing transaction, which
 * is what makes a series gapless and monotonic and lets a crash leave both the number and the
 * document or neither. Numbers are never reused; a void keeps its number (24B N-4); no series
 * ever resets (24B N-1).
 *
 * <p>M1 calls this from its location, position and device handlers, inside their transaction
 * (21A section 7), and keeps the series ids it gets back on the position or the location so
 * that a device assignment can move the counters. The kernel audits every call
 * ({@code SERIES_REGISTERED}, {@code SERIES_HOLDER_CHANGED}, {@code SERIES_CLOSED}) and
 * publishes {@code series.registered.v1}, {@code series.holder_changed.v1} and
 * {@code series.closed.v1}.
 *
 * <p>Taking a number is not on this interface: only {@link DocumentIssuance} does that, and only
 * for a validated draft.
 */
public interface NumberingService {

    /**
     * Registers a series, or returns the one already registered for the same type and scope:
     * a handler that runs twice (a retry, a replayed event) gets the same id and no second
     * counter.
     *
     * @return the series id
     * @throws ProblemException {@code series.type_unknown} for a code not in the registry;
     *                          {@code series.scope_invalid} when the scope does not fit the
     *                          type or the ids given; {@code series.closed} when the series of
     *                          that scope was closed
     */
    UUID registerSeries(SeriesRegistration registration, ScopeContext ctx);

    /**
     * Moves the counters of these series to another device: the shop's primary till changed,
     * or a position got a new device (21A: "counter transfer, audited"). The number is not
     * touched; the holder is who may increment it offline from now on.
     *
     * @throws ProblemException {@code series.not_found} for a series the caller cannot see;
     *                          {@code series.closed} for a closed one
     */
    void holderChange(Collection<UUID> seriesIds, UUID deviceId, ScopeContext ctx);

    /**
     * Closes a series for good: retiring a position closes its series (doc 18). A closed
     * series issues nothing and is never reopened; a new position gets a new series.
     */
    void closeSeries(UUID seriesId, ScopeContext ctx);

    /**
     * The active series registered for a holder: every series of a location (LOCATION scope)
     * or of a position (TILL_POSITION scope), for M1 to move or close them together.
     *
     * @param locationId     the location; required
     * @param tillPositionId the position, or null for the location's own series
     */
    List<UUID> activeSeriesOf(UUID ownerEntityId, UUID locationId, UUID tillPositionId);
}
