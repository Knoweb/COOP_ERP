package lk.coopfed.knoweb.m2catalogue.query;

import java.util.UUID;

/** One tax category (doc 22 section 3.6), for the SKU editor's picker. */
public record TaxCategoryView(UUID taxCategoryId, String code, String nameEn, String nameSi, String nameTa) {}
