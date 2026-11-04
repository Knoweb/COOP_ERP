package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * StockTakeSessionOpened (PGL-M2-09): emitted when a mobile stock-take session is opened/started.
 */
public record StockTakeSessionOpened(
        UUID sessionId, UUID ownerEntityId, UUID locationId, String scopeKind, int expectedLotsCount)
        implements DomainEvent {

    public static final String TYPE = "inventory.stock_take.session_opened.v1";
}
