package lk.coopfed.knoweb.m2catalogue.api;

/**
 * Command to define a new unit of measure (PGL-M2-04).
 * Only the Federation may define units of measure (cat.uom.manage).
 */
public record DefineUom(
        String uomCode, String nameEn, String nameSi, String nameTa, String description, boolean weight) {}
