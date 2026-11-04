package lk.coopfed.knoweb.m2catalogue.api;

import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a new unit of measure is defined (PGL-M2-04).
 */
public record UomDefined(String uomCode, String nameEn, String nameSi, String nameTa, boolean weight, String status)
        implements DomainEvent {

    public static final String TYPE = "catalogue.uom.defined.v1";
}
