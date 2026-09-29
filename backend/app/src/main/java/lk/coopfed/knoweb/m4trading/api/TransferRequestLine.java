package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;

/** A line of a transfer request event: an item and a quantity in its base unit. */
public record TransferRequestLine(UUID lineId, UUID skuId, BigDecimal qty) {}
