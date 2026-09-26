package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A unit conversion of a SKU (doc 22 section 3.2): {@code factorToBase} of the base unit make one
 * {@code uomCode}, from {@code effectiveFrom}; open-ended when {@code effectiveTo} is null. A
 * supplier's case-size change is a new effective-dated row, never an edit.
 */
public record DefineConversion(
        UUID skuId, String uomCode, BigDecimal factorToBase, LocalDate effectiveFrom, LocalDate effectiveTo) {}
