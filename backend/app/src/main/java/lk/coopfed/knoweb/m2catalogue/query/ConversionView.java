package lk.coopfed.knoweb.m2catalogue.query;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One effective-dated unit conversion of a SKU (doc 22 section 3.2). */
public record ConversionView(String uomCode, BigDecimal factorToBase, LocalDate effectiveFrom, LocalDate effectiveTo) {}
