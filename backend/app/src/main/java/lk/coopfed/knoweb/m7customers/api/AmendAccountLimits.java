package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * AmendLimit / SetHardBlock / SetOfflineCap (27A section 6), one command: a field left null stays
 * as it is. A reason is always given; a higher limit asks for a fresh second factor.
 *
 * @param nic the National Identity Card number, as typed, when the limit rises above
 *            {@code customers.nic_required_above_limit} and the customer holds none yet (wave 2,
 *            M7CR-03); only its hash and last four characters are kept
 */
public record AmendAccountLimits(
        UUID accountId, BigDecimal creditLimit, Boolean hardBlock, BigDecimal offlineCap, String reason, String nic) {

    /** The command without a NIC, as before wave 2. */
    public AmendAccountLimits(
            UUID accountId, BigDecimal creditLimit, Boolean hardBlock, BigDecimal offlineCap, String reason) {
        this(accountId, creditLimit, hardBlock, offlineCap, reason, null);
    }
}
