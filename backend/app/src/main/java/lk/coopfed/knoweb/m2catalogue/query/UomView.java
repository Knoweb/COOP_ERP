package lk.coopfed.knoweb.m2catalogue.query;

/** One unit of the Federation's vocabulary (doc 22 section 3.2), for the SKU editor's pickers. */
public record UomView(String uomCode, String nameEn, String nameSi, String nameTa, boolean weight) {}
