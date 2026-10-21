package lk.coopfed.knoweb.m2catalogue.api;

/**
 * Define a new tax category in the catalogue (22A section 6; doc 22 section 3.6).
 * Only the Federation may define tax categories.
 */
public record DefineTaxCategory(String code, String nameEn, String nameSi, String nameTa) {}
