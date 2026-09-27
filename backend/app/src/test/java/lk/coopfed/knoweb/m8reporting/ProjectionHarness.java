package lk.coopfed.knoweb.m8reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The equivalence harness of 28A section 9 ("the first test you write"): the rows a live
 * consumer leaves after a stream of events, with the redeliveries a live system sees, must
 * equal the rows a rebuild from zero leaves after the same events once each, in archive order.
 *
 * <p>A rebuild here is what the procedure in the module README does: the privileged role
 * empties the projection's tables and the events are replayed through the same consumer.
 * Each event is delivered as the kernel's dispatcher delivers it: the envelope of a consumer of
 * every type, in the OWN scope of the event's owner, at the location the outbox read from the
 * payload's {@code locationId}, with no user.
 */
public final class ProjectionHarness {

    private final ObjectMapper mapper;
    private final JdbcTemplate admin;

    public ProjectionHarness(ObjectMapper mapper, JdbcTemplate admin) {
        this.mapper = mapper;
        this.admin = admin;
    }

    /** An event as the outbox would hold it, ready to deliver. */
    public record Delivery(JsonNode envelope, ScopeContext scope) {}

    /** Wraps a module's event record in the kernel's envelope, owned by the entity. */
    public Delivery event(String type, UUID owner, Instant occurredAt, Object payload) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventType", type);
        envelope.put("eventId", Ids.next().toString());
        envelope.put("ownerEntityId", owner.toString());
        envelope.put("occurredAt", occurredAt.toString());
        JsonNode body = mapper.valueToTree(payload);
        envelope.set("payload", body);
        String location = body.path("locationId").asText(null);
        return new Delivery(envelope, system(owner, location == null ? null : UUID.fromString(location)));
    }

    /** The scope the dispatcher gives a consumer: OWN, of the owner, no user. */
    public static ScopeContext system(UUID owner, UUID location) {
        Scope scope = new Scope(owner, location);
        return new ScopeContext(
                null, null, owner, List.of(scope), scope, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    /**
     * Delivers the events live, each one once in order and a random earlier one again after
     * some of them (the redelivery of an at-least-once broker, a replay of a range), reads the
     * tables, empties them, replays every event once in order and reads them again.
     *
     * @return the live rows and the rebuilt rows, per table, for the caller to compare
     */
    public Result liveThenRebuild(
            List<Delivery> events, BiConsumer<JsonNode, ScopeContext> consumer, List<String> tables, long seed) {
        Random random = new Random(seed);
        empty(tables);
        for (int i = 0; i < events.size(); i++) {
            deliver(events.get(i), consumer);
            if (random.nextInt(3) == 0) {
                deliver(events.get(random.nextInt(i + 1)), consumer);
            }
        }
        Map<String, List<Map<String, Object>>> live = read(tables);
        empty(tables);
        for (Delivery event : events) {
            deliver(event, consumer);
        }
        return new Result(live, read(tables));
    }

    public record Result(Map<String, List<Map<String, Object>>> live, Map<String, List<Map<String, Object>>> rebuilt) {}

    public void deliver(Delivery event, BiConsumer<JsonNode, ScopeContext> consumer) {
        consumer.accept(event.envelope(), event.scope());
    }

    public void empty(List<String> tables) {
        admin.execute("truncate table reporting.projection_state, " + String.join(", ", tables));
    }

    /** Every row of each table, in the order of all its columns, as the superuser sees them. */
    public Map<String, List<Map<String, Object>>> read(List<String> tables) {
        Map<String, List<Map<String, Object>>> rows = new java.util.LinkedHashMap<>();
        for (String table : tables) {
            List<String> columns = admin.queryForList(
                    "select column_name from information_schema.columns"
                            + " where table_schema || '.' || table_name = ? order by ordinal_position",
                    String.class,
                    table);
            List<String> order = new ArrayList<>();
            for (int i = 1; i <= columns.size(); i++) {
                order.add(String.valueOf(i));
            }
            rows.put(table, admin.queryForList("select * from " + table + " order by " + String.join(", ", order)));
        }
        return rows;
    }
}
