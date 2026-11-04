package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a new unit of measure is defined (PGL-M2-04).
 * Holds aggregate identity uomId derived deterministically from the UOM code.
 */
public record UomDefined(
        UUID uomId, String uomCode, String nameEn, String nameSi, String nameTa, boolean weight, String status)
        implements DomainEvent {

    public static final String TYPE = "catalogue.uom.defined.v1";
}
