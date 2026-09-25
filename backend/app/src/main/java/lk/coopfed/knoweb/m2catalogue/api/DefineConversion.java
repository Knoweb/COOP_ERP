package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DefineConversion(UUID skuId, String fromUom, String toUom, BigDecimal factor, LocalDate validFrom) {}
