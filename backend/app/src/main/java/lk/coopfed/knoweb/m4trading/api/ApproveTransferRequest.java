package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * ApproveLateralTransfer (doc 24 section 4.7): the society approves; M5 issues the transfer.
 *
 * @param fromLocationId the society's location that gives the stock; null keeps the one the shop
 *                       named (required when the shop named none: a shop session sees no other
 *                       location of its society)
 */
public record ApproveTransferRequest(UUID requestId, UUID fromLocationId) {

    /** Approves the source the shop named. */
    public ApproveTransferRequest(UUID requestId) {
        this(requestId, null);
    }
}
