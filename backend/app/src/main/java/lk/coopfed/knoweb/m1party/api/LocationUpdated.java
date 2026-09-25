package lk.coopfed.knoweb.m1party.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * The facts a till and the fleet work from changed: language, trading hours, size band, the
 * connectivity gate (doc 21 section 7; the update windows of doc 31 and the day-open of doc 32
 * read them). Published by UpdateLocation and by ConfirmLocationConnectivity. Doc 21 section 5.3
 * lists no update event; this one is added so that those consumers hear of a change.
 */
public record LocationUpdated(
        UUID updatedLocationId,
        UUID ownerEntityId,
        String language,
        List<TradingDay> tradingHours,
        String sizeBand,
        boolean connectivitySpecMet)
        implements DomainEvent {

    public static final String TYPE = "location.updated.v1";
}
