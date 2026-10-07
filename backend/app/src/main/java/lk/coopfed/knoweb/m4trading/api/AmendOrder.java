package lk.coopfed.knoweb.m4trading.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * AmendOrder (24A section 6): the buyer changes its order before the seller decides it: the
 * lines, their quantities and the delivery date it asks for. The amendment is the next version
 * of the order, a new document; the order it amends is closed (CANCELLED, reason ORDER_AMENDED).
 * A submitted order's next version is submitted at once, and the seller accepts it afresh.
 *
 * @param lines every line of the next version (the whole set, not a difference)
 */
public record AmendOrder(UUID orderId, String reason, LocalDate requestedEta, String notes, List<CreateOrder.Line> lines) {}
