package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * ClaimReturn (25A section 6.2): the goods of an approved claim leave the buyer's stock at the
 * location they were received at, back to the seller. Built by {@link ClaimReturnConsumer} from
 * M4's {@code claim.return_dispatched.v1}.
 */
record ApplyClaimReturn(UUID claimId, UUID buyerEntityId, UUID locationId, List<Line> lines) {

    record Line(UUID claimLineId, UUID batchId, BigDecimal qty) {}
}
