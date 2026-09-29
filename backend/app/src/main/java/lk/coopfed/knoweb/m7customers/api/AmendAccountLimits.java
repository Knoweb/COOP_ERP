package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * AmendLimit / SetHardBlock / SetOfflineCap (27A section 6), one command: a field left null stays
 * as it is. A reason is always given; a higher limit asks for a fresh second factor.
 */
public record AmendAccountLimits(
        UUID accountId, BigDecimal creditLimit, Boolean hardBlock, BigDecimal offlineCap, String reason) {}
