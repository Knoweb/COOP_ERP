package lk.coopfed.knoweb.m8reporting.internal.projection;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The credit limit of each trading pair (wave 2, M8-08, decision D6; CR-28A-2):
 * {@code credit_limit_fact}, one row per {@code credit_limit.changed.v1}, by the field names of
 * M1's {@code CreditLimitChanged}. M1 publishes it when a limit is amended, when a relationship is
 * activated with a limit (null to the opening limit) and once for a relationship activated before
 * activation published it (M1's credit-limit backfill). The exposure view (TradeSql.EXPOSURE)
 * takes the latest row of each (seller, buyer) as the current limit: an amendment makes a new
 * relationship row, so the pair, not the relationship id, is what a limit belongs to.
 *
 * <p>Written in the OWN scope of the event's owner (the seller, or whoever activated), naming the
 * other party as the counterparty, who reads it through party_read. The location is the
 * publishing session's, NULL for the entity-wide work it is. Only ever inserted, keyed by the
 * event.
 */
@Component
public class CreditLimitProjection extends Projection {

    public static final String NAME = "credit_limit";

    static final String CREDIT_LIMIT_CHANGED = "credit_limit.changed.v1";

    CreditLimitProjection(JdbcTemplate jdbc, ProjectionStateStore state) {
        super(NAME, Set.of(CREDIT_LIMIT_CHANGED), jdbc, state);
    }

    @EventConsumer(types = "*", consumer = "m8." + NAME)
    @Transactional
    public void on(JsonNode envelope, ScopeContext scope) {
        consume(envelope, scope);
    }

    @Override
    protected void apply(ProjectionEvent event, ScopeContext scope) {
        UUID owner = scope.entityId();
        UUID seller = event.uuid("sellerEntityId");
        UUID buyer = event.uuid("buyerEntityId");
        if (seller == null || buyer == null) {
            // Published before wave 2 added the pair to the event: it cannot be filed under a
            // pair, and a row without one would only be refused. The pair shows from its next
            // change of limit.
            return;
        }
        UUID counterparty = owner.equals(seller) ? buyer : seller;
        LocalDate effectiveFrom = event.date("effectiveFrom");
        jdbc.update(
                """
                insert into reporting.credit_limit_fact
                       (event_id, relationship_id, previous_relationship_id, owner_entity_id, counterparty_entity_id,
                        location_id, seller_entity_id, buyer_entity_id, credit_limit, previous_credit_limit,
                        effective_from, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (event_id) do nothing
                """,
                event.eventId(),
                event.uuid("relationshipId"),
                event.uuid("previousRelationshipId"),
                owner,
                counterparty,
                scope.locationId(),
                seller,
                buyer,
                event.decimal("creditLimit"),
                event.decimal("previousCreditLimit"),
                effectiveFrom == null ? null : Date.valueOf(effectiveFrom),
                Timestamp.from(event.occurredAt()));
    }
}
