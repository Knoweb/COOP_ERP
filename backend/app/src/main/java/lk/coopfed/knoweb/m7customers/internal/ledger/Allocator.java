package lk.coopfed.knoweb.m7customers.internal.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;

/**
 * The allocation of a payment to the charges it settles (27A section 6.3). Pure: it computes, the
 * handler writes. Oldest first by business date, then by when central received the charge; or the
 * charges the officer chose, each no more than what is open of it. What is left of the payment
 * stays unallocated on the account (a credit balance), never refused.
 */
public final class Allocator {

    private Allocator() {}

    /**
     * A charge with something still open: its amount less what earlier credits settled.
     *
     * @param documentId the till receipt the charge came from (or the adjustment), so a void's
     *                   CREDIT finds the charge it undoes
     */
    public record OpenCharge(
            UUID postingId, UUID documentId, LocalDate businessDate, Instant receivedAt, BigDecimal open) {}

    /** What the payment (or the credit) puts against one charge. */
    public record Allocation(UUID chargePostingId, BigDecimal amount) {}

    /**
     * How a CREDIT settles charges (wave 2, CR-27A-1 item 2): first the open charge of the same
     * document (a void's {@code receipt.voided.v1} carries the receipt's own id, so the voided
     * charge is found; a refund's document is the refund receipt and usually matches nothing),
     * then oldest first for what remains.
     */
    public static List<Allocation> sameDocumentFirst(List<OpenCharge> open, UUID documentId, BigDecimal amount) {
        List<Allocation> allocations = new ArrayList<>();
        BigDecimal remaining = amount;
        List<OpenCharge> rest = new ArrayList<>();
        for (OpenCharge charge : open) {
            if (documentId != null && documentId.equals(charge.documentId()) && remaining.signum() > 0) {
                BigDecimal take = charge.open().min(remaining);
                if (take.signum() > 0) {
                    allocations.add(new Allocation(charge.postingId(), take));
                    remaining = remaining.subtract(take);
                }
            } else {
                rest.add(charge);
            }
        }
        allocations.addAll(oldestFirst(rest, remaining));
        return allocations;
    }

    /** Oldest first: each charge in turn, as far as the amount goes. */
    public static List<Allocation> oldestFirst(List<OpenCharge> open, BigDecimal amount) {
        List<OpenCharge> ordered = new ArrayList<>(open);
        ordered.sort((a, b) -> {
            int byDate = a.businessDate().compareTo(b.businessDate());
            return byDate != 0 ? byDate : a.receivedAt().compareTo(b.receivedAt());
        });
        List<Allocation> allocations = new ArrayList<>();
        BigDecimal remaining = amount;
        for (OpenCharge charge : ordered) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = charge.open().min(remaining);
            if (take.signum() > 0) {
                allocations.add(new Allocation(charge.postingId(), take));
                remaining = remaining.subtract(take);
            }
        }
        return allocations;
    }

    /**
     * The charges the officer chose. Refused: a charge that is not an open charge of this account,
     * named twice, an amount of zero or less, more than is open of it ({@code
     * m7.payment.exceeds_open}), or more in all than the payment ({@code m7.payment.exceeds_amount}).
     */
    public static List<Allocation> specific(List<OpenCharge> open, List<Allocation> chosen, BigDecimal amount) {
        Map<UUID, OpenCharge> byId = new HashMap<>();
        open.forEach(charge -> byId.put(charge.postingId(), charge));
        Set<UUID> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Allocation allocation : chosen) {
            if (allocation == null || allocation.chargePostingId() == null || !seen.add(allocation.chargePostingId())) {
                throw new ProblemException("m7.payment.charge_invalid");
            }
            OpenCharge charge = byId.get(allocation.chargePostingId());
            if (charge == null) {
                throw new ProblemException(
                        "m7.payment.charge_invalid",
                        Map.of("chargePostingId", allocation.chargePostingId().toString()));
            }
            if (allocation.amount() == null || allocation.amount().signum() <= 0) {
                throw new ProblemException("m7.payment.amount_invalid");
            }
            if (allocation.amount().compareTo(charge.open()) > 0) {
                throw new ProblemException(
                        "m7.payment.exceeds_open",
                        Map.of(
                                "chargePostingId",
                                charge.postingId().toString(),
                                "open",
                                charge.open().toPlainString()));
            }
            sum = sum.add(allocation.amount());
        }
        if (sum.compareTo(amount) > 0) {
            throw new ProblemException("m7.payment.exceeds_amount");
        }
        return List.copyOf(chosen);
    }
}
