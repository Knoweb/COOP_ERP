package lk.coopfed.knoweb.m4trading.internal.queries;

import java.math.BigDecimal;
import java.time.Instant;

/** The order's status as the screens show it, derived from the two parties' records (CR-24A-1). */
public final class OrderStatus {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String LOCKED = "LOCKED";
    public static final String PARTIALLY_FULFILLED = "PARTIALLY_FULFILLED";
    public static final String FULFILLED = "FULFILLED";

    private OrderStatus() {}

    public static String derive(
            String documentStatus,
            String allocationStatus,
            Instant lockAt,
            Instant now,
            BigDecimal allocated,
            BigDecimal fulfilled) {
        if (!SUBMITTED.equals(documentStatus) || allocationStatus == null) {
            return documentStatus;
        }
        if (REJECTED.equals(allocationStatus)) {
            return REJECTED;
        }
        if (fulfilled != null && fulfilled.signum() > 0) {
            return fulfilled.compareTo(allocated) >= 0 ? FULFILLED : PARTIALLY_FULFILLED;
        }
        if (lockAt != null && !lockAt.isAfter(now)) {
            return LOCKED;
        }
        return ACCEPTED;
    }
}
