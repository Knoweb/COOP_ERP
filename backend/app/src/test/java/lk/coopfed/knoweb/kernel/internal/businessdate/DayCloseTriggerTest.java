package lk.coopfed.knoweb.kernel.internal.businessdate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lk.coopfed.knoweb.kernel.api.DayClose;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DayCloseTriggerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID entityId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 10, 16);

    private final List<UUID> closedLocations = new ArrayList<>();
    private final AtomicReference<LocalDate> currentTradingDate = new AtomicReference<>(today);

    private DayClose dayClose;
    private DayCloseTrigger trigger;

    @BeforeEach
    void setUp() {
        closedLocations.clear();
        currentTradingDate.set(today);

        dayClose = new DayClose() {
            @Override
            public LocalDate closeForDate(UUID locationId, LocalDate expectedDate, ScopeContext ctx) {
                if (expectedDate != null && !expectedDate.isEqual(currentTradingDate.get())) {
                    return currentTradingDate.get();
                }
                closedLocations.add(locationId);
                return currentTradingDate.get().plusDays(1);
            }
        };
        trigger = new DayCloseTrigger(dayClose);
    }

    @Test
    void advancesDayWhenValidSessionsClosedEventReceivedForToday() {
        ObjectNode payload = mapper.createObjectNode()
                .put("closedLocationId", shopId.toString())
                .put("businessDate", today.toString());

        trigger.onSessionsClosed(payload, scope(entityId, shopId));

        assertThat(closedLocations).containsExactly(shopId);
    }

    @Test
    void ignoresEventWhenScopeHasNoLocationEvenIfPayloadNamesLocation() {
        // Wave 2, TWK-23 security invariant: envelope location is authoritative, never payload's.
        ObjectNode payload = mapper.createObjectNode()
                .put("closedLocationId", shopId.toString())
                .put("locationId", shopId.toString())
                .put("businessDate", today.toString());

        trigger.onSessionsClosed(payload, scope(entityId, null));

        assertThat(closedLocations).isEmpty();
    }

    @Test
    void ignoresPayloadLocationWhenDifferingFromEnvelopeScopeLocation() {
        // Wave 2, TWK-23 security invariant: payload location redirection is ignored.
        UUID elsewhere = UUID.randomUUID();
        ObjectNode payload = mapper.createObjectNode()
                .put("closedLocationId", elsewhere.toString())
                .put("locationId", elsewhere.toString())
                .put("businessDate", today.toString());

        trigger.onSessionsClosed(payload, scope(entityId, shopId));

        assertThat(closedLocations).containsExactly(shopId);
        assertThat(closedLocations).doesNotContain(elsewhere);
    }

    @Test
    void ignoresDelayedEventForPastBusinessDate() {
        LocalDate yesterday = today.minusDays(1);
        ObjectNode payload = mapper.createObjectNode()
                .put("closedLocationId", shopId.toString())
                .put("businessDate", yesterday.toString());

        trigger.onSessionsClosed(payload, scope(entityId, shopId));

        assertThat(closedLocations).isEmpty();
    }

    @Test
    void ignoresEventForFutureBusinessDate() {
        LocalDate tomorrow = today.plusDays(1);
        ObjectNode payload = mapper.createObjectNode()
                .put("closedLocationId", shopId.toString())
                .put("businessDate", tomorrow.toString());

        trigger.onSessionsClosed(payload, scope(entityId, shopId));

        assertThat(closedLocations).isEmpty();
    }

    @Test
    void handlesEventWithMissingBusinessDateGracefully() {
        ObjectNode payload = mapper.createObjectNode().put("closedLocationId", shopId.toString());

        trigger.onSessionsClosed(payload, scope(entityId, shopId));

        assertThat(closedLocations).containsExactly(shopId);
    }

    @Test
    void handlesEventWithUnparseableBusinessDateGracefully() {
        ObjectNode payload = mapper.createObjectNode()
                .put("closedLocationId", shopId.toString())
                .put("businessDate", "not-a-date");

        trigger.onSessionsClosed(payload, scope(entityId, shopId));

        assertThat(closedLocations).isEmpty();
    }

    private static ScopeContext scope(UUID entity, UUID location) {
        Scope active = new Scope(entity, location);
        return new ScopeContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }
}
