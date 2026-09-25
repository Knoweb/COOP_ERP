package lk.coopfed.knoweb.m1party.internal.location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentType;
import lk.coopfed.knoweb.kernel.api.DocumentTypes;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.kernel.api.SeriesScope;
import org.springframework.stereotype.Component;

/**
 * The calls M1 makes to the kernel's numbering service (21A section 4, "SeriesHooks (calls
 * NumberingService)"; 19A section 7). Every method runs inside the calling handler's
 * transaction; the kernel audits and publishes what it does (series.registered.v1,
 * series.holder_changed.v1, series.closed.v1), so those records commit or roll back with the
 * handler's own.
 *
 * <p>Which series exist is read from the document type registry, never listed here: a position
 * gets one series for every type numbered per till (RCT, CPR today), a shop one for every type
 * numbered per location (GRN, WOF, CNT, RPK, XFR). A warehouse or an office gets none: those
 * types fall back to the entity's series there (seed/kernel/document-types.yaml).
 */
@Component
class SeriesHooks {

    private final NumberingService numbering;
    private final DocumentTypes types;

    SeriesHooks(NumberingService numbering, DocumentTypes types) {
        this.numbering = numbering;
        this.types = types;
    }

    /**
     * The location series of a shop, registered where missing (registration is idempotent), so a
     * shop registered before this hook, or by a seed, gets them when its primary till is named.
     * Nothing for a location that is not a shop.
     */
    List<UUID> registerLocationSeries(Location location, String entityCode, ScopeContext scope) {
        if (!location.isShop()) {
            return List.of();
        }
        List<UUID> seriesIds = new ArrayList<>();
        for (DocumentType type : typesNumberedAt(SeriesScope.LOCATION)) {
            seriesIds.add(numbering.registerSeries(
                    SeriesRegistration.forLocation(
                            type.code(),
                            location.ownerEntityId(),
                            location.getId(),
                            entityCode,
                            location.locationCode(),
                            null),
                    scope));
        }
        return seriesIds;
    }

    /** The till series of a new position; no device holds them until one is assigned (M1-06). */
    List<UUID> registerPositionSeries(Location location, TillPosition position, String entityCode, ScopeContext scope) {
        List<UUID> seriesIds = new ArrayList<>();
        for (DocumentType type : typesNumberedAt(SeriesScope.TILL_POSITION)) {
            seriesIds.add(numbering.registerSeries(
                    SeriesRegistration.forTillPosition(
                            type.code(),
                            location.ownerEntityId(),
                            location.getId(),
                            position.getId(),
                            entityCode,
                            location.locationCode(),
                            position.positionNo(),
                            null),
                    scope));
        }
        return seriesIds;
    }

    /** The counters of the location series move to the device at the new primary till. */
    void moveLocationSeriesTo(List<UUID> locationSeriesIds, UUID deviceId, ScopeContext scope) {
        if (!locationSeriesIds.isEmpty()) {
            numbering.holderChange(locationSeriesIds, deviceId, scope);
        }
    }

    /** Closes every open series of a retiring position; returns the ids it closed. */
    List<UUID> closePositionSeries(TillPosition position, ScopeContext scope) {
        List<UUID> open = numbering.activeSeriesOf(position.ownerEntityId(), position.locationId(), position.getId());
        for (UUID seriesId : open) {
            numbering.closeSeries(seriesId, scope);
        }
        return open;
    }

    private List<DocumentType> typesNumberedAt(SeriesScope scope) {
        return types.all().stream()
                .filter(type -> type.seriesScope() == scope)
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .toList();
    }
}
