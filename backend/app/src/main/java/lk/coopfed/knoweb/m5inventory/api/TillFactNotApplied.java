package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A till uploaded a stock fact M5 has no hook for yet (wave 2, M5-04/M5-05; decided 6 October 2026:
 * {@code docs/progress/deviations/2026-10-06-wave2-stock-movements.md} (6)): a count, a write-off
 * request, a repack or a transfer recorded at the till. The fact is kept by the kernel and never
 * refused; M5 records it as {@code TILL_FACT_NOT_APPLIED} (REVIEW) so it is visible until its hook
 * is built, and announces it here.
 *
 * @param factType   the till's event type, as uploaded (for example {@code count.recorded.v1})
 * @param documentId the document the till's bundle names, or null when it names none
 */
public record TillFactNotApplied(UUID ownerEntityId, UUID locationId, UUID deviceId, String factType, UUID documentId)
        implements DomainEvent {

    public static final String TYPE = "till_fact.not_applied.v1";
}
