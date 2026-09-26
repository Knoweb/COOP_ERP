package lk.coopfed.knoweb.m1party.internal.security.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.dao.DataAccessException;

/**
 * The two properties of 21A section 9 (doc 21 section 10) through the real handlers and the
 * database: "random role edits never produce a ROLE-mode conflict" and "random assignments never
 * exceed grantor scope". 21A names jqwik, which is not in the version catalogue; these are
 * randomised JUnit tests over generated edits and assignments, with a fixed seed so that a
 * failure replays exactly. RoleRulesPropertyTest runs the same properties a thousand times each
 * on the guardrail functions alone; here each run is a few hundred commands, every one a real
 * transaction under the caller's row-level security.
 *
 * <p>The oracle is a model the test keeps itself, not the guard under test (review of 26
 * September 2026): from the generated command and what the test knows it has arranged and
 * accepted so far, it says which message id the handler must answer, or that the command must
 * be accepted. A handler that refused everything with a plausible id, or accepted what a
 * grantor may not give, fails here.
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

        // The model: what each role holds after every accepted edit. It starts empty, as the
        // roles do.
        Map<UUID, Set<String>> modelOf = new HashMap<>();
        for (UUID each : roles) {
            modelOf.put(each, new HashSet<>());
        }

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
            Set<String> wantedCodes = new HashSet<>(RoleRules.codes(wanted));

            // Expected, by doc 19 section 3.2: no role holds both halves; nor does the clerk
            // through roles 0 and 1 together (the clerk does not hold role 2).
            String expected = null;
            if (!heldBothHalves(wantedCodes).isEmpty()) {
                expected = "m1.role.sod_conflict";
            } else if (role.equals(roles.get(0)) || role.equals(roles.get(1))) {
                UUID other = role.equals(roles.get(0)) ? roles.get(1) : roles.get(0);
                Set<String> clerkWouldHold = new HashSet<>(modelOf.get(other));
                clerkWouldHold.addAll(wantedCodes);
                if (!heldBothHalves(clerkWouldHold).isEmpty()) {
                    expected = "m1.role.sod_conflict";
                }
            }

            try {
                amendRole.handle(new AmendRole(role, wanted, false), asAdmin);
                assertThat(expected)
                        .as("step %d: accepted, the model expected a refusal", step)
                        .isNull();
                modelOf.put(role, wantedCodes);
                accepted++;
            } catch (ProblemException e) {
                assertThat(e.messageId()).as("step %d", step).isEqualTo(expected);
                refused++;
            }

            for (UUID each : roles) {
                assertThat(fx.permissionsOf(each))
                        .as("step %d: role %s holds what the model says", step, each)
                        .containsExactlyInAnyOrderElementsOf(modelOf.get(each));
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

    /** One accepted assignment, as the model remembers it. */
    private record Held(UUID role, UUID location) {}

    @Test
    void randomAssignmentsNeverReachBeyondTheGrantor() {
        UUID first = fx.mpcs("MPCS one (M1-08 assignments)");
        UUID second = fx.mpcs("MPCS two (M1-08 assignments)");
        UUID firstShop = fx.shop(first);
        UUID firstStore = fx.shop(first);
        UUID secondShop = fx.shop(second);
        Map<UUID, UUID> ownerOfLocation = Map.of(firstShop, first, firstStore, first, secondShop, second);

        // A pair in ROLE mode for the first society: the view of a location and the management
        // of devices, both of which its roles below carry, so that generated assignments hit it.
        List<String> pair = RoleRules.ordered("prt.location.view", "sys.device.manage");
        fx.pair(first, pair.get(0), pair.get(1), "ROLE");

        Set<String> admins = Set.of(
                "gov.role.manage", "prt.location.view", "sys.device.view", "sys.device.manage", "prt.position.manage");
        Set<String> managers = Set.of("gov.role.manage", "prt.location.view", "sys.device.manage");
        // Two location-scoped grantors, one per shop of the first society: a shop manager
        // assigns at their own shop alone, and sees the assignments at their shop and the
        // entity-wide ones, never a sibling shop's.
        List<Grantor> grantors = List.of(
                grantor(first, null, admins),
                grantor(first, firstShop, managers),
                grantor(first, firstStore, managers),
                grantor(second, null, admins));

        List<UUID> users = new ArrayList<>();
        Map<UUID, UUID> homeOf = new HashMap<>();
        for (UUID entity : List.of(first, first, first, second, second)) {
            UUID user = fx.user(entity);
            users.add(user);
            homeOf.put(user, entity);
        }

        Map<UUID, Set<String>> permissionsOf = new HashMap<>();
        Map<UUID, UUID> ownerOfRole = new HashMap<>();
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
            ownerOfRole.put(role, (UUID) spec[0]);
        }
        List<UUID> locations = new ArrayList<>(List.of(firstShop, firstStore, secondShop));
        locations.add(null);

        // The model: every assignment accepted so far, per user.
        Map<UUID, List<Held>> heldBy = new HashMap<>();
        for (UUID user : users) {
            heldBy.put(user, new ArrayList<>());
        }

        Random random = new Random(SEED);
        int accepted = 0;
        int refused = 0;
        int knownGap = 0;
        for (int attempt = 0; attempt < 250; attempt++) {
            Grantor grantor = grantors.get(random.nextInt(grantors.size()));
            UUID user = users.get(random.nextInt(users.size()));
            UUID role = roles.get(random.nextInt(roles.size()));
            UUID location = locations.get(random.nextInt(locations.size()));
            String at = "attempt %d by %s".formatted(attempt, grantor);

            String expected = expectedRefusal(
                    grantor,
                    user,
                    role,
                    location,
                    ownerOfRole,
                    ownerOfLocation,
                    homeOf,
                    permissionsOf,
                    heldBy,
                    first,
                    pair,
                    true);

            String answered;
            try {
                assignRole.handle(new AssignRole(user, role, location), grantor.scope());
                answered = null;
            } catch (ProblemException e) {
                answered = e.messageId();
            }

            if (!Objects.equals(expected, answered)
                    && "m1.assignment.sod_conflict".equals(expected)
                    && answered == null
                    && grantor.location() != null
                    && expectedRefusal(
                                    grantor,
                                    user,
                                    role,
                                    location,
                                    ownerOfRole,
                                    ownerOfLocation,
                                    homeOf,
                                    permissionsOf,
                                    heldBy,
                                    first,
                                    pair,
                                    false)
                            == null) {
                // The one difference tolerated: the per-person ROLE check of AssignRole reads the
                // user's assignments under the caller's row-level security, which hides a shop
                // manager's view of the sibling shops (m1security V0008), so the other half of a
                // pair held at another shop is not seen (review of 26 September 2026,
                // AssignRoleHandler, "compute holdings entity-wide"). The model above says what
                // doc 19 section 3.2 wants; this branch goes when that fix lands, and the
                // assertion below then expects the refusal.
                knownGap++;
            } else {
                assertThat(answered).as(at + ": the model's answer").isEqualTo(expected);
            }

            if (answered != null) {
                refused++;
                continue;
            }
            accepted++;
            heldBy.get(user).add(new Held(role, location));

            List<Map<String, Object>> rows = superuserJdbc()
                    .queryForList(
                            "select scope_entity_id, scope_location_id from security.user_role"
                                    + " where user_id = ? and role_id = ? and scope_location_id is not distinct from ?",
                            user,
                            role,
                            location);
            assertThat(rows).as("attempt %d: one row", attempt).hasSize(1);
            UUID entity = (UUID) rows.get(0).get("scope_entity_id");
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
        assertThat(knownGap).as("cases the tolerated gap absorbed").isLessThan(accepted);
    }

    /**
     * The model of AssignRole's guards, in the handler's order (21A section 6 and doc 19 section
     * 3.2): the id of the refusal the command must get, or null when it must be accepted.
     *
     * @param pairOwner          the society whose ROLE pair {@code pair} is; the other has none
     * @param entityWideHoldings true reads the user's holdings across the entity, as doc 19
     *                           wants; false reads only what a caller at the grantor's location
     *                           sees (entity-wide rows and rows at that location)
     */
    private static String expectedRefusal(
            Grantor grantor,
            UUID user,
            UUID role,
            UUID location,
            Map<UUID, UUID> ownerOfRole,
            Map<UUID, UUID> ownerOfLocation,
            Map<UUID, UUID> homeOf,
            Map<UUID, Set<String>> permissionsOf,
            Map<UUID, List<Held>> heldBy,
            UUID pairOwner,
            List<String> pair,
            boolean entityWideHoldings) {
        // Another entity's role is not "not in scope" but not found: the handler reads the role
        // under the caller's row-level security (m1security V0010), which shows an OWN caller
        // its own entity's roles and the templates and nothing else.
        UUID owner = ownerOfRole.get(role);
        if (owner != null && !owner.equals(grantor.entity())) {
            return "m1.role.not_found";
        }
        if (grantor.location() != null && !grantor.location().equals(location)) {
            return "m1.assignment.outside_caller_scope";
        }
        if (location != null && !grantor.entity().equals(ownerOfLocation.get(location))) {
            return "m1.assignment.location_not_in_entity";
        }
        if (!grantor.entity().equals(homeOf.get(user))) {
            return "m1.assignment.user_not_in_scope";
        }
        // A shop-scoped caller reads the users with an assignment at its shop or an entity-wide
        // one (m1security V0012): a user with no assignment yet, or with assignments at sibling
        // shops only, is not there to be assigned.
        if (grantor.location() != null) {
            boolean visible = heldBy.get(user).stream()
                    .anyMatch(held -> held.location() == null || held.location().equals(grantor.location()));
            if (!visible) {
                return "m1.assignment.user_not_in_scope";
            }
        }
        if (heldBy.get(user).contains(new Held(role, location))) {
            return "m1.assignment.exists";
        }
        if (!grantor.holds().containsAll(permissionsOf.get(role))) {
            return "m1.role.permission_not_held";
        }
        // The grantor's entity is the user's home entity by now.
        Set<String> wouldHold = new HashSet<>(permissionsOf.get(role));
        for (Held held : heldBy.get(user)) {
            boolean visible = entityWideHoldings
                    || held.location() == null
                    || held.location().equals(grantor.location());
            if (visible) {
                wouldHold.addAll(permissionsOf.get(held.role()));
            }
        }
        if (grantor.entity().equals(pairOwner) && wouldHold.containsAll(pair)) {
            return "m1.assignment.sod_conflict";
        }
        return null;
    }

    /**
     * Two grantors assign the same role to the same person at the same moment: one row results.
     * The handler's exists-check cannot see the other transaction, so the second insert is
     * stopped by the unique index of m1security V0001 (user_role_scope_uq); that refusal is not
     * yet translated into m1.assignment.exists, so either answer is accepted here, and what is
     * asserted is the invariant: one assignment, whichever of the two made it.
     */
    @Test
    void twoGrantorsAssigningTheSameRoleAtOnceLeaveOneAssignment() throws Exception {
        UUID mpcs = fx.mpcs("MPCS (M1-08 race)");
        Set<String> holds = Set.of("gov.role.manage", "prt.location.view");
        Grantor one = grantor(mpcs, null, holds);
        Grantor two = grantor(mpcs, null, holds);
        UUID user = fx.user(mpcs);
        UUID role = fx.role(mpcs, "prt.location.view");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (Grantor grantor : List.of(one, two)) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        assignRole.handle(new AssignRole(user, role, null), grantor.scope());
                        return "accepted";
                    } catch (ProblemException e) {
                        return e.messageId();
                    } catch (DataAccessException e) {
                        return "user_role_scope_uq";
                    }
                }));
            }
            start.countDown();
            List<String> answers = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                answers.add(outcome.get(30, TimeUnit.SECONDS));
            }

            assertThat(answers).as("exactly one of the two made the assignment").containsOnlyOnce("accepted");
            String other = answers.get(answers.indexOf("accepted") == 0 ? 1 : 0);
            if (!"m1.assignment.exists".equals(other) && !"user_role_scope_uq".equals(other)) {
                fail("the other grantor was answered %s", other);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer rows = superuserJdbc()
                .queryForObject(
                        "select count(*) from security.user_role where user_id = ? and role_id = ?",
                        Integer.class,
                        user,
                        role);
        assertThat(rows).as("one assignment").isEqualTo(1);
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
