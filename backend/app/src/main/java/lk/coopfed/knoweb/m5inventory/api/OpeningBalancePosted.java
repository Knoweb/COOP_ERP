package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * opening_balance.posted.v1 (doc 25 section 5.3: "location, lines"): the countersigned balance is
 * in stock; the OPB document cites the movements. M1's location activation gate may wait for it
 * (doc 25 DR-6).
 *
 * @param lines how many counted lines were posted
 */
public record OpeningBalancePosted(
        UUID openingBalanceId, UUID ownerEntityId, UUID locationId, UUID documentId, int lines) implements DomainEvent {

    public static final String TYPE = "opening_balance.posted.v1";
}
