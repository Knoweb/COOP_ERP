package lk.coopfed.knoweb.m1party.internal.security.role;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.event.DeadLetter;
import lk.coopfed.knoweb.kernel.internal.event.EventConsumerDispatcher;
import lk.coopfed.knoweb.kernel.internal.event.EventConsumerRegistry;
import lk.coopfed.knoweb.kernel.internal.event.InboxGuard;
import lk.coopfed.knoweb.kernel.internal.event.OutboxMessage;
import lk.coopfed.knoweb.m1party.api.AssignRole;
import lk.coopfed.knoweb.m1party.api.RevokeRole;
import lk.coopfed.knoweb.m1party.api.RoleAssigned;
import lk.coopfed.knoweb.m1party.api.RoleRevoked;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * After a revoke, the kernel's resolver stops allowing the permission (M1-08 with K-03b): the
 * revoke writes {@code role.revoked.v1} to the outbox with the user's id, the kernel's consumer
 * framework delivers that very row to the permission cache's consumer
 * ({@code kernel-permission-cache}, PermissionCacheInvalidator), and the next resolution no
 * longer holds the permission. Until the event is delivered the cache still answers from
 * memory, which is the staleness the event exists to end.
 *
 * <p>The dispatcher is the kernel's own EventConsumerDispatcher, built here with a broker that
 * drops what it is given, as EventConsumerFrameworkPostgresIntegrationTest builds it: this
 * application context runs no broker, so nothing else would deliver the row.
 */
class RevokeStopsThePermissionPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String CACHE_CONSUMER = "kernel-permission-cache";

    @Autowired
    Handles<AssignRole, UUID> assignRole;

    @Autowired
    Handles<RevokeRole, UUID> revokeRole;

    @Autowired
    PermissionResolver resolver;

    @Autowired
    EventConsumerRegistry registry;

    @Autowired
    InboxGuard inbox;

    @Autowired
    AuditFacade audit;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    SecurityFixture fx;
    UUID mpcs;
    UUID shop;
    UUID admin;
    UUID clerk;
    UUID shopRole;

    @BeforeEach
    void arrange() {
        fx = new SecurityFixture(superuserJdbc());
        fx.clean();
        mpcs = fx.mpcs("MPCS (M1-08 revoke)");
        shop = fx.shop(mpcs);
        admin = fx.user(mpcs);
        fx.assign(
                admin,
                fx.role(mpcs, "gov.role.manage", "gov.user.manage", "prt.location.view", "sys.device.view"),
                mpcs,
                null);
        clerk = fx.user(mpcs);
        shopRole = fx.role(mpcs, "prt.location.view", "sys.device.view");
    }

    @AfterEach
    void cleanUp() {
        fx.clean();
    }

    @Test
    void afterARevokeTheResolverStopsAllowingThePermission() throws Exception {
        ScopeContext asAdmin = ScopeContext.dev(admin, mpcs, null);
        ScopeContext clerkAtShop = ScopeContext.dev(clerk, mpcs, shop);

        assignRole.handle(new AssignRole(clerk, shopRole, shop), asAdmin);
        assertThat(kernel.committedEvents()).containsExactly(new RoleAssigned(shopRole, clerk, mpcs, shop));
        assertThat(inScope(clerkAtShop, () -> resolver.allows(clerkAtShop, "sys.device.view")))
                .as("the clerk holds the permission at the shop")
                .isTrue();

        revokeRole.handle(new RevokeRole(clerk, shopRole, shop, "moved"), asAdmin);
        assertThat(kernel.committedEvents()).endsWith(new RoleRevoked(shopRole, clerk, mpcs, shop));
        // The instance that revoked empties its own cache when the revoke commits (the kernel's
        // PublishedEventListener): the permission is gone here at once. The other instances
        // hear it through the event below.
        assertThat(inScope(clerkAtShop, () -> resolver.allows(clerkAtShop, "sys.device.view")))
                .as("on the revoking instance the permission is gone at once")
                .isFalse();

        OutboxMessage revoked = outboxRow(RoleRevoked.TYPE, shopRole);
        JsonNode payload = mapper.readTree(revoked.payload());
        assertThat(payload.path("userId").asText())
                .as("the invalidator reads userId to empty that user's entries")
                .isEqualTo(clerk.toString());

        EventConsumerDispatcher dispatcher = new EventConsumerDispatcher(
                registry, inbox, new DeadLetter(message -> {}), audit, mapper, jdbc, transactionManager);
        assertThat(dispatcher.deliver(CACHE_CONSUMER, revoked, 1))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.APPLIED);

        assertThat(inScope(clerkAtShop, () -> resolver.allows(clerkAtShop, "sys.device.view")))
                .as("after the event, the permission is gone")
                .isFalse();
        assertThat(inScope(clerkAtShop, () -> resolver.resolve(clerkAtShop))).isEmpty();
    }

    /** The row the outbox holds for the event, as the relay would hand it to the broker. */
    private OutboxMessage outboxRow(String type, UUID aggregateId) {
        List<OutboxMessage> rows = superuserJdbc()
                .query(
                        """
                        select event_id, event_type, occurred_at, source, source_seq, owner_entity_id, location_id,
                               aggregate_type, aggregate_id, correlation_id, causation_id, actor_user_id,
                               engine_version, payload::text as payload
                          from kernel.event_outbox
                         where event_type = ? and aggregate_id = ?
                         order by source_seq desc
                        """,
                        (rs, i) -> new OutboxMessage(
                                rs.getObject("event_id", UUID.class),
                                rs.getString("event_type"),
                                rs.getTimestamp("occurred_at").toInstant(),
                                rs.getString("source"),
                                rs.getLong("source_seq"),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getString("aggregate_type"),
                                rs.getObject("aggregate_id", UUID.class),
                                rs.getObject("correlation_id", UUID.class),
                                rs.getObject("causation_id", UUID.class),
                                rs.getObject("actor_user_id", UUID.class),
                                rs.getString("engine_version"),
                                rs.getString("payload")),
                        type,
                        aggregateId);
        assertThat(rows).as("an outbox row of " + type).isNotEmpty();
        return rows.get(0);
    }

    private <T> T inScope(ScopeContext scope, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', ?, true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    scope.userId().toString(),
                    Ids.next().toString(),
                    scope.entityId().toString(),
                    scope.locationId() == null ? "" : scope.locationId().toString());
            return work.get();
        });
    }
}
