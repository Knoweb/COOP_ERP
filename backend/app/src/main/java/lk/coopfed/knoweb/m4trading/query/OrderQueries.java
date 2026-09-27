package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The order queries of doc 24 section 5.2. Row-level security decides what a caller sees: the
 * buyer its orders, the seller the orders placed with it, the Federation view all.
 */
public interface OrderQueries {

    Optional<OrderView> getOrder(UUID orderId, ScopeContext scope);

    /**
     * ListOrders(role, status): the orders in which the caller's entity is the buyer or the seller,
     * newest first; {@code status} filters on the derived status when given.
     */
    List<OrderView> listOrders(Role role, String status, ScopeContext scope);

    enum Role {
        BUYER,
        SELLER
    }
}
