package lk.coopfed.knoweb.m1party.internal.security.role;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.AmendRole;
import lk.coopfed.knoweb.m1party.api.AssignRole;
import lk.coopfed.knoweb.m1party.api.RolePermission;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two properties of 21A section 9 (doc 21 section 10) through the real handlers and the
 * database: "random role edits never produce a ROLE-mode conflict" and "random assignments never
 * exceed grantor scope". 21A names jqwik, which is not in the version catalogue; these are
 * randomised JUnit tests over generated edits and assignments, with a fixed seed so that a
 * failure replays exactly. RoleRulesPropertyTest runs the same properties a thousand times each
 * on the guardrail functions alone; here each run is a few hundred commands, every one a real
 * transaction under the caller's row-level security.
 */
class RoleGuardrailsPropertyPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final long SEED = 20260925L;

    private static final List<String> CODES = List.of(
            "prt.location.view",
            "prt.location.manage",
            "sys.device.view",
            "sys.device.manage",
            "prt.position.manage",
            "prt.relationship.view",
            "prt.relationship.manage");

    private static final List<List<String>> ROLE_PAIRS = List.of(
            RoleRules.ordered("prt.position.manage", "sys.device.manage"),
            RoleRules.ordered("prt.location.manage", "prt.relationship.manage"),
            RoleRules.ordered("prt.relationship.view", "sys.device.view"));

    @Autowired
    Handles<AmendRole, UUID> amendRole;

    @Autowired
    Handles<AssignRole, UUID> assignRole;

    SecurityFixture fx;

    @BeforeEach
    void arrange() {
        fx = new SecurityFixture(superuserJdbc());
        fx.clean();
    }

    @AfterEach
    void cleanUp() {
        fx.clean();
    }

    @Test
    void randomRoleEditsNeverLeaveARoleOrAPersonHoldingARoleModePair() {
        UUID mpcs = fx.mpcs("MPCS (M1-08 edits)");
        UUID shop = fx.shop(mpcs);
        UUID admin = fx.user(mpcs);
        List<String> adminHolds = new ArrayList<>(CODES);
        adminHolds.add("gov.role.manage");
        adminHolds.add("gov.user.manage");
        fx.assign(admin, fx.role(mpcs, adminHolds.toArray(String[]::new)), mpcs, null);
        for (List<String> pair : ROLE_PAIRS) {
            fx.pair(mpcs, pair.get(0), pair.get(1), "ROLE");
        }

        List<UUID> roles = List.of(fx.role(mpcs), fx.role(mpcs), fx.role(mpcs));
        UUID clerk = fx.user(mpcs);
        fx.assign(clerk, roles.get(0), mpcs, null);
        fx.assign(clerk, roles.get(1), mpcs, shop);

        Random random = new Random(SEED);
        ScopeContext asAdmin = ScopeContext.dev(admin, mpcs, null);
        int accepted = 0;
        int refused = 0;
        for (int step = 0; step < 200; step++) {
            UUID role = roles.get(random.nextInt(roles.size()));
            List<RolePermission> wanted = new ArrayList<>();
            for (String code : CODES) {
                if (random.nextInt(3) == 0) {
                    wanted.add(RolePermission.of(code));
                }
            }
            try {
                amendRole.handle(new AmendRole(role, wanted, false), asAdmin);
                accepted++;
            } catch (ProblemException e) {
                assertThat(e.messageId()).as("step %d", step).isEqualTo("m1.role.sod_conflict");
                refused++;
            }

            for (UUID each : roles) {
                assertThat(heldBothHalves(Set.copyOf(fx.permissionsOf(each))))
                        .as("step %d: role %s holds a ROLE pair", step, each)
                        .isEmpty();
            }
            Set<String> clerkHolds = new HashSet<>(fx.permissionsOf(roles.get(0)));
            clerkHolds.addAll(fx.permissionsOf(roles.get(1)));
            assertThat(heldBothHalves(clerkHolds))
                    .as("step %d: the clerk holds a ROLE pair through two roles", step)
                    .isEmpty();
        }
        assertThat(accepted).as("edits accepted").isGreaterThan(20);
        assertThat(refused).as("edits refused").isGreaterThan(20);
    }

    /** One caller of the assignment property: who, where, and what they hold there. */
    private record Grantor(UUID user, UUID entity, UUID location, Set<String> holds) {
        ScopeContext scope() {
            return ScopeContext.dev(user, entity, location);
        }
    }

    @Test
    void randomAssignmentsNeverReachBeyondTheGrantor() {
        UUID first = fx.mpcs("MPCS one (M1-08 assignments)");
        UUID second = fx.mpcs("MPCS two (M1-08 assignments)");
        UUID firstShop = fx.shop(first);
        UUID firstStore = fx.shop(first);
        UUID secondShop = fx.shop(second);
        Map<UUID, UUID> ownerOfLocation = Map.of(firstShop, first, firstStore, first, secondShop, second);

        Set<String> admins = Set.of("gov.role.manage", "prt.location.view", "sys.device.view", "prt.position.manage");
        Set<String> managers = Set.of("gov.role.manage", "prt.location.view");
        List<Grantor> grantors = List.of(
                grantor(first, null, admins), grantor(first, firstShop, managers), grantor(second, null, admins));

        List<UUID> users = new ArrayList<>();
        Map<UUID, UUID> homeOf = new java.util.HashMap<>();
        for (UUID entity : List.of(first, first, first, second, second)) {
            UUID user = fx.user(entity);
            users.add(user);
            homeOf.put(user, entity);
        }

        Map<UUID, Set<String>> permissionsOf = new java.util.HashMap<>();
        List<UUID> roles = new ArrayList<>();
        for (Object[] spec : new Object[][] {
            {first, new String[] {"prt.location.view"}},
            {first, new String[] {"sys.device.view", "prt.position.manage"}},
            {first, new String[] {"sys.device.manage"}},
            {second, new String[] {"prt.location.view"}},
            {null, new String[] {"prt.location.view"}}
        }) {
            String[] codes = (String[]) spec[1];
            UUID role = fx.role((UUID) spec[0], codes);
            roles.add(role);
            permissionsOf.put(role, Set.of(codes));
        }
        List<UUID> locations = new ArrayList<>(List.of(firstShop, firstStore, secondShop));
        locations.add(null);

        Random random = new Random(SEED);
        int accepted = 0;
        int refused = 0;
        for (int attempt = 0; attempt < 150; attempt++) {
            Grantor grantor = grantors.get(random.nextInt(grantors.size()));
            UUID user = users.get(random.nextInt(users.size()));
            UUID role = roles.get(random.nextInt(roles.size()));
            UUID location = locations.get(random.nextInt(locations.size()));
            try {
                assignRole.handle(new AssignRole(user, role, location), grantor.scope());
                accepted++;
            } catch (ProblemException e) {
                refused++;
                continue;
            }

            List<Map<String, Object>> rows = superuserJdbc()
                    .queryForList(
                            "select scope_entity_id, scope_location_id from security.user_role"
                                    + " where user_id = ? and role_id = ? and scope_location_id is not distinct from ?",
                            user,
                            role,
                            location);
            assertThat(rows).as("attempt %d: one row", attempt).hasSize(1);
            UUID entity = (UUID) rows.get(0).get("scope_entity_id");
            String at = "attempt %d by %s".formatted(attempt, grantor);
            assertThat(entity).as(at + ": the grantor's entity").isEqualTo(grantor.entity());
            assertThat(homeOf.get(user))
                    .as(at + ": a user of the grantor's entity")
                    .isEqualTo(grantor.entity());
            if (location != null) {
                assertThat(ownerOfLocation.get(location))
                        .as(at + ": a location of the grantor's entity")
                        .isEqualTo(grantor.entity());
            }
            if (grantor.location() != null) {
                assertThat(location).as(at + ": at the grantor's location").isEqualTo(grantor.location());
            }
            assertThat(grantor.holds())
                    .as(at + ": nothing the grantor does not hold")
                    .containsAll(permissionsOf.get(role));
        }
        assertThat(accepted).as("assignments made").isGreaterThan(5);
        assertThat(refused).as("assignments refused").isGreaterThan(20);
    }

    private Grantor grantor(UUID entity, UUID location, Set<String> holds) {
        UUID user = fx.user(entity);
        fx.assign(user, fx.role(entity, holds.toArray(String[]::new)), entity, location);
        return new Grantor(user, entity, location, holds);
    }

    private static List<List<String>> heldBothHalves(Set<String> permissions) {
        return ROLE_PAIRS.stream().filter(pair -> permissions.containsAll(pair)).toList();
    }
}
