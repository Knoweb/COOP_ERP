package lk.coopfed.knoweb.m4trading.internal.order;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import org.springframework.stereotype.Component;

/** The guards the order handlers share. */
@Component
public class OrderGuards {

    private final DocumentBaseRepository documents;
    private final RelationshipQueries relationships;
    private final TradingClock clock;

    OrderGuards(DocumentBaseRepository documents, RelationshipQueries relationships, TradingClock clock) {
        this.documents = documents;
        this.relationships = relationships;
        this.clock = clock;
    }

    /** An order the caller can see; {@code m4.order.not_found} otherwise. */
    public DocumentRecord visibleOrder(UUID orderId) {
        return documents
                .findById(orderId)
                .filter(document -> OrderTypeHandler.ORD.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.order.not_found"));
    }

    /** The buyer's own order: only the owner of a document writes it (AGENTS.md idea 3). */
    public DocumentRecord ownOrder(UUID orderId, ScopeContext scope) {
        DocumentRecord order = visibleOrder(orderId);
        if (!order.ownerEntityId().equals(scope.entityId())) {
            throw new ProblemException("m4.order.not_buyer");
        }
        // Locked for the transaction: two commands on one order serialise here.
        return documents.findByIdForUpdate(orderId).orElseThrow();
    }

    /** The seller's view of an order placed with it. */
    public DocumentRecord sellersOrder(UUID orderId, ScopeContext scope) {
        DocumentRecord order = visibleOrder(orderId);
        if (!scope.entityId().equals(order.counterpartyEntityId())) {
            throw new ProblemException("m4.order.not_seller");
        }
        return order;
    }

    /**
     * The relationship of the order's seller and buyer in force today (M1 LookupRelationship),
     * ACTIVE. The order names the row it was drafted under; an amendment of the terms (a new
     * credit limit, say) closes that row and opens the next one (21A section 6.1), and the pair
     * still trades, so the order is judged by the row in force today, not by the row it names.
     * Accepted on the architect's delegation, 29 September 2026: a limit change must not strand
     * the open orders of the pair.
     */
    public RelationshipView activeRelationship(UUID relationshipId, ScopeContext scope) {
        return relationships
                .getRelationship(relationshipId, scope)
                .flatMap(drafted -> relationships.lookupRelationship(
                        drafted.sellerEntityId(), drafted.buyerEntityId(), clock.today(), scope))
                .filter(row -> "ACTIVE".equals(row.status()))
                .orElseThrow(() -> new ProblemException("m4.order.relationship_inactive"));
    }
}
