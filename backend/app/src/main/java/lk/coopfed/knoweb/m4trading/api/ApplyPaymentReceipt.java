package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;

/**
 * ApplyReceipt (24A section 6.3): the seller's accounts apply money a receipt left on the buyer's
 * account (its unapplied amount) to invoices issued since. With no settlements chosen, the buyer's
 * open, undisputed invoices oldest first, as far as the money goes.
 */
public record ApplyPaymentReceipt(UUID receiptId, List<RecordPaymentReceipt.Settlement> settlements) {

    public ApplyPaymentReceipt {
        settlements = settlements == null ? List.of() : List.copyOf(settlements);
    }
}
