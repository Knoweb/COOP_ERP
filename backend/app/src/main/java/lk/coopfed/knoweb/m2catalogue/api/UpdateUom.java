package lk.coopfed.knoweb.m2catalogue.api;

/**
 * Command to update an existing unit of measure (PGL-M2-04).
 * Only the Federation may update units of measure (cat.uom.manage).
 */
public record UpdateUom(
        String uomCode,
        String nameEn,
        String nameSi,
        String nameTa,
        String description,
        boolean weight,
        String status) {}
