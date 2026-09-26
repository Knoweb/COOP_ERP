package lk.coopfed.knoweb.m1party.internal.security.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.AmendRole;
import lk.coopfed.knoweb.m1party.api.AssignRole;
import lk.coopfed.knoweb.m1party.api.CreateRole;
import lk.coopfed.knoweb.m1party.api.RemoveSodPair;
import lk.coopfed.knoweb.m1party.api.RetireRole;
import lk.coopfed.knoweb.m1party.api.RevokeRole;
import lk.coopfed.knoweb.m1party.api.RoleAssigned;
import lk.coopfed.knoweb.m1party.api.RoleChanged;
import lk.coopfed.knoweb.m1party.api.RolePermission;
import lk.coopfed.knoweb.m1party.api.RoleRevoked;
import lk.coopfed.knoweb.m1party.api.SetSodPair;
import lk.coopfed.knoweb.m1party.api.SodPairChanged;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every handler of M1-08 against PostgreSQL, under the caller's own row-level security: the
 * success of each with what it audited and published, and every guard of 21A section 6 as a
 * failing case with its message id and nothing committed (AGENTS.md, "every handler test
 * asserts what was audited and published").
 *
 * <p>The handlers are called directly, as a till batch or a job would call them: the kernel
 * puts the scope on the transaction, and the permission check of the HTTP interceptor is not
 * what is under test here. The state each test starts from is written as the superuser
 * ({@link SecurityFixture}); every user is new in every test, so the kernel's permission cache
 * holds nothing from an earlier one.
 */
class RoleHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String[] ADMIN_PERMISSIONS = {
        "gov.role.manage",
        "gov.user.manage",
        "prt.location.view",
        "prt.location.manage",
        "sys.device.view",
        "prt.position.manage"
    };

    @Autowired
    Handles<CreateRole, UUID> createRole;

    @Autowired
    Handles<AmendRole, UUID> amendRole;

    @Autowired
    Handles<RetireRole, UUID> retireRole;

    @Autowired
    Handles<AssignRole, UUID> assignRole;

    @Autowired
    Handles<RevokeRole, UUID> revokeRole;

    @Autowired
    Handles<SetSodPair, UUID> setSodPair;

    @Autowired
    Handles<RemoveSodPair, UUID> removeSodPair;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    SecurityFixture fx;
    UUID federation;
    UUID mpcs;
    UUID otherMpcs;
    UUID shop;
    UUID secondShop;
    UUID otherShop;
    UUID admin;
    UUID adminRole;
    UUID federationAdmin;
    UUID clerk;
    UUID template;

    @BeforeEach
    void arrange() {
        fx = new SecurityFixture(superuserJdbc());
        fx.clean();
        federation = fx.federation();
        mpcs = fx.mpcs("MPCS A (M1-08)");
        otherMpcs = fx.mpcs("MPCS B (M1-08)");
        shop = fx.shop(mpcs);
        secondShop = fx.shop(mpcs);
        otherShop = fx.shop(otherMpcs);

        admin = fx.user(mpcs);
        adminRole = fx.role(mpcs, ADMIN_PERMISSIONS);
        fx.assign(admin, adminRole, mpcs, null);

        federationAdmin = fx.user(federation);
        UUID federationRole = fx.role(
                federation,
                "gov.role.manage",
                "gov.user.manage",
                "prt.location.view",
                "sys.device.view",
                "prt.position.manage",
                "gov.entity.view");
        fx.assign(federationAdmin, federationRole, federation, null);

        clerk = fx.user(mpcs);
        template = fx.role(null, "prt.location.view", "sys.device.view");
    }

    @AfterEach
    void cleanUp() {
        fx.clean();
    }

    private ScopeContext asAdmin() {
        return ScopeContext.dev(admin, mpcs, null);
    }

    private ScopeContext asFederation() {
        return ScopeContext.dev(federationAdmin, federation, null);
    }

    private static List<RolePermission> perms(String... codes) {
        return Arrays.stream(codes).map(RolePermission::of).toList();
    }

    private void refused(Runnable command, String messageId) {
        assertThatThrownBy(command::run)
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo(messageId));
        assertThat(kernel.committedAudit()).as("nothing audited").isEmpty();
        assertThat(kernel.committedEvents()).as("nothing published").isEmpty();
    }

    private CreateRole named(String name, List<RolePermission> permissions) {
        return new CreateRole(SecurityFixture.NAME_PREFIX + name, null, null, null, permissions, false, null);
    }

    @Nested
    class CreatingARole {

        @Test
        void fromScratchWritesTheRoleAuditsAndPublishes() {
            UUID roleId = createRole.handle(named("clerk", perms("prt.location.view", "sys.device.view")), asAdmin());

            assertThat(fx.permissionsOf(roleId)).containsExactly("prt.location.view", "sys.device.view");
            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select owner_entity_id from security.role where role_id = ?", UUID.class, roleId))
                    .isEqualTo(mpcs);
            assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
                assertThat(record.eventType()).isEqualTo("ROLE_CHANGED");
                assertThat(record.subject().type()).isEqualTo("role");
                assertThat(record.subject().id()).isEqualTo(roleId);
                assertThat(record.before()).isNull();
                assertThat(permissionKeys(record.after()))
                        .containsExactlyInAnyOrder("prt.location.view", "sys.device.view");
            });
            assertThat(kernel.committedEvents())
                    .containsExactly(new RoleChanged(
                            roleId,
                            mpcs,
                            1,
                            "ACTIVE",
                            false,
                            RoleChanged.CREATED,
                            List.of("prt.location.view", "sys.device.view"),
                            List.of()));
        }

        @Test
        void aCloneTakesTheTemplatesPermissionsAndRemembersItsVersion() {
            UUID roleId = createRole.handle(
                    new CreateRole(SecurityFixture.NAME_PREFIX + "clone", null, null, template, null, false, null),
                    asAdmin());

            assertThat(fx.permissionsOf(roleId)).containsExactly("prt.location.view", "sys.device.view");
            Map<String, Object> row = superuserJdbc()
                    .queryForMap(
                            "select template_role_id, template_version_seen from security.role where role_id = ?",
                            roleId);
            assertThat(row.get("template_role_id")).isEqualTo(template);
            assertThat(row.get("template_version_seen")).isEqualTo(1);
        }

        @Test
        void theFederationAuthorsTemplatesAndItsOwnRolesMayCarryFederationPermissions() {
            UUID newTemplate = createRole.handle(
                    new CreateRole(
                            SecurityFixture.NAME_PREFIX + "template",
                            null,
                            null,
                            null,
                            perms("prt.location.view"),
                            true,
                            null),
                    asFederation());
            UUID ownRole = createRole.handle(named("fed viewer", perms("gov.entity.view")), asFederation());

            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select owner_entity_id is null and is_template from security.role where role_id = ?",
                                    Boolean.class,
                                    newTemplate))
                    .isTrue();
            assertThat(fx.permissionsOf(ownRole)).containsExactly("gov.entity.view");
        }

        @Test
        void aLocationScopeIsRefused() {
            refused(
                    () -> createRole.handle(
                            named("x", perms("prt.location.view")), ScopeContext.dev(admin, mpcs, shop)),
                    "m1.role.entity_scope_required");
        }

        @Test
        void onlyTheFederationAuthorsATemplate() {
            refused(
                    () -> createRole.handle(
                            new CreateRole(SecurityFixture.NAME_PREFIX + "t", null, null, null, perms(), true, null),
                            asAdmin()),
                    "m1.role.federation_required");
        }

        @Test
        void aNameOfSpacesIsRefused() {
            refused(
                    () -> createRole.handle(new CreateRole("   ", null, null, null, perms(), false, null), asAdmin()),
                    "m1.role.name_required");
        }

        @Test
        void aNameTheEntityUsesAlreadyIsRefused() {
            createRole.handle(named("twice", perms()), asAdmin());
            kernel.reset();
            refused(
                    () -> createRole.handle(
                            new CreateRole(
                                    SecurityFixture.NAME_PREFIX.toUpperCase() + "TWICE",
                                    null,
                                    null,
                                    null,
                                    perms(),
                                    false,
                                    null),
                            asAdmin()),
                    "m1.role.name_taken");
        }

        @Test
        void aTemplateThatIsNotATemplateIsRefused() {
            refused(
                    () -> createRole.handle(
                            new CreateRole(SecurityFixture.NAME_PREFIX + "c", null, null, adminRole, null, false, null),
                            asAdmin()),
                    "m1.role.template_not_found");
        }

        @Test
        void aClassOtherThanOwnIsTheFederations() {
            refused(
                    () -> createRole.handle(
                            new CreateRole(
                                    SecurityFixture.NAME_PREFIX + "v",
                                    null,
                                    null,
                                    null,
                                    perms(),
                                    false,
                                    "FEDERATION_VIEW"),
                            asAdmin()),
                    "m1.role.class_not_permitted");
            refused(
                    () -> createRole.handle(
                            new CreateRole(SecurityFixture.NAME_PREFIX + "v", null, null, null, perms(), false, "BOTH"),
                            asAdmin()),
                    "m1.role.class_invalid");
        }

        @Test
        void aCodeOutsideTheCatalogueIsRefused() {
            refused(
                    () -> createRole.handle(named("u", perms("no.such.permission")), asAdmin()),
                    "m1.role.permission_unknown");
        }

        @Test
        void nobodyGrantsAPermissionTheyDoNotHold() {
            refused(
                    () -> createRole.handle(named("esc", perms("prt.location.view", "sys.device.manage")), asAdmin()),
                    "m1.role.permission_not_held");
        }

        @Test
        void aFederationPermissionStaysOutOfAnEntityRole() {
            // Even when the entity's administrator somehow holds one (written here as the superuser).
            superuserJdbc()
                    .update(
                            "insert into security.role_permission (role_id, permission_code) values (?, 'gov.entity.view')",
                            adminRole);
            refused(
                    () -> createRole.handle(named("fed", perms("gov.entity.view")), asAdmin()),
                    "m1.role.permission_federation_only");
        }

        @Test
        void aRoleModePairIsNeverHeldByOneRole() {
            fx.pair(mpcs, "prt.location.view", "prt.position.manage", "ROLE");
            assertThatThrownBy(() -> createRole.handle(
                            named("both", perms("prt.location.view", "prt.position.manage")), asAdmin()))
                    .isInstanceOf(ProblemException.class)
                    .satisfies(e -> {
                        ProblemException problem = (ProblemException) e;
                        assertThat(problem.messageId()).isEqualTo("m1.role.sod_conflict");
                        assertThat(problem.parameters())
                                .containsEntry("permissionA", "prt.location.view")
                                .containsEntry("permissionB", "prt.position.manage");
                    });
            // The same pair in INSTANCE mode is the acting module's to enforce, not the role's.
            UUID unrelated = createRole.handle(
                    named("both ok elsewhere", perms("prt.location.view", "sys.device.view")), asAdmin());
            assertThat(unrelated).isNotNull();
        }

        @Test
        void limitsAreCheckedAgainstThePermissionsSchema() {
            fx.permission(
                    "tst.m108.approve",
                    "ENTITY",
                    "{\"properties\":{\"max_value\":{\"type\":\"number\",\"minimum\":0}},\"required\":[\"max_value\"]}");
            superuserJdbc()
                    .update(
                            "insert into security.role_permission (role_id, permission_code) values (?, 'tst.m108.approve')",
                            adminRole);

            refused(
                    () -> createRole.handle(
                            named("neg", List.of(new RolePermission("tst.m108.approve", Map.of("max_value", -1)))),
                            asAdmin()),
                    "m1.role.limits_invalid");
            refused(
                    () -> createRole.handle(
                            named("lim", List.of(new RolePermission("prt.location.view", Map.of("max_value", 5)))),
                            asAdmin()),
                    "m1.role.limits_not_accepted");

            UUID roleId = createRole.handle(
                    named("ok", List.of(new RolePermission("tst.m108.approve", Map.of("max_value", 25000)))),
                    asAdmin());
            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select limits ->> 'max_value' from security.role_permission where role_id = ?",
                                    String.class,
                                    roleId))
                    .isEqualTo("25000");
        }
    }

    @Nested
    class AmendingARole {

        @Test
        void theSetIsReplacedTheVersionRaisedAndTheDiffPublished() {
            UUID roleId = fx.role(mpcs, "prt.location.view", "sys.device.view");

            amendRole.handle(new AmendRole(roleId, perms("sys.device.view", "prt.position.manage"), false), asAdmin());

            assertThat(fx.permissionsOf(roleId)).containsExactly("prt.position.manage", "sys.device.view");
            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select version from security.role where role_id = ?", Integer.class, roleId))
                    .isEqualTo(2);
            assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
                assertThat(record.eventType()).isEqualTo("ROLE_CHANGED");
                assertThat(permissionKeys(record.before()))
                        .containsExactlyInAnyOrder("prt.location.view", "sys.device.view");
                assertThat(permissionKeys(record.after()))
                        .containsExactlyInAnyOrder("prt.position.manage", "sys.device.view");
            });
            assertThat(kernel.committedEvents())
                    .containsExactly(new RoleChanged(
                            roleId,
                            mpcs,
                            2,
                            "ACTIVE",
                            false,
                            RoleChanged.AMENDED,
                            List.of("prt.position.manage"),
                            List.of("prt.location.view")));
        }

        @Test
        void aRoleNobodyCanSeeIsNotFound() {
            UUID theirs = fx.role(otherMpcs, "prt.location.view");
            refused(() -> amendRole.handle(new AmendRole(theirs, perms(), false), asAdmin()), "m1.role.not_found");
        }

        @Test
        void aTemplateIsTheFederationsToChange() {
            refused(
                    () -> amendRole.handle(new AmendRole(template, perms("prt.location.view"), false), asAdmin()),
                    "m1.role.not_owner");
        }

        @Test
        void aRetiredRoleIsNotChanged() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            superuserJdbc().update("update security.role set status = 'RETIRED' where role_id = ?", roleId);
            refused(() -> amendRole.handle(new AmendRole(roleId, perms(), false), asAdmin()), "m1.role.retired");
        }

        @Test
        void theLastUserManagerOfTheEntityIsNotTakenAway() {
            List<String> withoutUserManage = Arrays.stream(ADMIN_PERMISSIONS)
                    .filter(code -> !code.equals("gov.user.manage"))
                    .toList();
            refused(
                    () -> amendRole.handle(
                            new AmendRole(
                                    adminRole,
                                    withoutUserManage.stream()
                                            .map(RolePermission::of)
                                            .toList(),
                                    false),
                            asAdmin()),
                    "m1.role.last_user_manager");
        }

        @Test
        void aChangeThatWouldGiveAHolderBothHalvesOfARolePairIsRefused() {
            UUID viewRole = fx.role(mpcs, "prt.location.view");
            UUID deviceRole = fx.role(mpcs, "sys.device.view");
            fx.assign(clerk, viewRole, mpcs, null);
            fx.assign(clerk, deviceRole, mpcs, shop);
            fx.pair(mpcs, "prt.location.view", "prt.position.manage", "ROLE");

            assertThatThrownBy(() -> amendRole.handle(
                            new AmendRole(deviceRole, perms("sys.device.view", "prt.position.manage"), false),
                            asAdmin()))
                    .isInstanceOf(ProblemException.class)
                    .satisfies(e -> {
                        assertThat(((ProblemException) e).messageId()).isEqualTo("m1.role.sod_conflict");
                        assertThat(((ProblemException) e).parameters()).containsEntry("userId", clerk);
                    });
            assertThat(kernel.committedEvents()).isEmpty();
        }

        @Test
        void aTemplateHeldOutsideTheFederationKeepsUserManageAndTakesNoFederationCode() {
            UUID adminTemplate = fx.role(null, "gov.user.manage", "prt.location.view");
            fx.assign(clerk, adminTemplate, mpcs, null);

            // Dropping gov.user.manage would take the society's last user manager away.
            assertThatThrownBy(() -> amendRole.handle(
                            new AmendRole(adminTemplate, perms("prt.location.view"), false), asFederation()))
                    .isInstanceOf(ProblemException.class)
                    .satisfies(e -> {
                        assertThat(((ProblemException) e).messageId())
                                .isEqualTo("m1.role.template_held_outside_federation");
                        assertThat(((ProblemException) e).parameters())
                                .containsEntry("permissions", "gov.user.manage")
                                .containsEntry("assignments", 1L);
                    });
            // Adding a FEDERATION-scope code would hand it to every society administrator.
            refused(
                    () -> amendRole.handle(
                            new AmendRole(
                                    adminTemplate,
                                    perms("gov.user.manage", "prt.location.view", "gov.entity.view"),
                                    false),
                            asFederation()),
                    "m1.role.template_held_outside_federation");
            // Any other change to the template is the Federation's to make.
            amendRole.handle(
                    new AmendRole(adminTemplate, perms("gov.user.manage", "sys.device.view"), false), asFederation());
            assertThat(fx.permissionsOf(adminTemplate)).containsExactly("gov.user.manage", "sys.device.view");
        }

        @Test
        void aTemplateHeldByTheFederationAloneOrByNobodyChangesFreely() {
            UUID adminTemplate = fx.role(null, "gov.user.manage", "prt.location.view");
            fx.assign(federationAdmin, adminTemplate, federation, null);
            UUID departed = fx.user(mpcs, "DEACTIVATED");
            fx.assign(departed, adminTemplate, mpcs, null);

            amendRole.handle(
                    new AmendRole(adminTemplate, perms("prt.location.view", "gov.entity.view"), false), asFederation());

            assertThat(fx.permissionsOf(adminTemplate)).containsExactly("gov.entity.view", "prt.location.view");
        }

        @Test
        void adoptingATemplateVersionNeedsATemplate() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            refused(
                    () -> amendRole.handle(new AmendRole(roleId, perms("prt.location.view"), true), asAdmin()),
                    "m1.role.no_template");
        }
    }

    @Nested
    class RetiringARole {

        @Test
        void aRoleNobodyHoldsIsRetired() {
            UUID roleId = fx.role(mpcs, "prt.location.view");

            retireRole.handle(new RetireRole(roleId), asAdmin());

            assertThat(superuserJdbc()
                            .queryForObject("select status from security.role where role_id = ?", String.class, roleId))
                    .isEqualTo("RETIRED");
            assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
                assertThat(record.eventType()).isEqualTo("ROLE_CHANGED");
                assertThat(record.after()).isEqualTo(Map.of("status", "RETIRED"));
            });
            assertThat(kernel.committedEvents())
                    .containsExactly(new RoleChanged(
                            roleId, mpcs, 1, "RETIRED", false, RoleChanged.RETIRED, List.of(), List.of()));
        }

        @Test
        void aRoleSomebodyHoldsIsNotRetired() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            fx.assign(clerk, roleId, mpcs, shop);
            refused(() -> retireRole.handle(new RetireRole(roleId), asAdmin()), "m1.role.has_assignments");
        }

        @Test
        void aTemplateAssignedInAnotherEntityIsNotRetiredByTheFederation() {
            UUID otherUser = fx.user(otherMpcs);
            fx.assign(otherUser, template, otherMpcs, null);
            refused(() -> retireRole.handle(new RetireRole(template), asFederation()), "m1.role.has_assignments");
        }
    }

    @Nested
    class AssigningARole {

        @Test
        void aRoleIsAssignedAtALocationAuditedAndPublished() {
            UUID roleId = fx.role(mpcs, "prt.location.view");

            assignRole.handle(new AssignRole(clerk, roleId, shop), asAdmin());

            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select scope_location_id from security.user_role where user_id = ? and role_id = ?",
                                    UUID.class,
                                    clerk,
                                    roleId))
                    .isEqualTo(shop);
            assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
                assertThat(record.eventType()).isEqualTo("ROLE_ASSIGNED");
                assertThat(record.subject().type()).isEqualTo("user");
                assertThat(record.subject().id()).isEqualTo(clerk);
                assertThat(((Map<?, ?>) record.after()).get("scopeLocationId")).isEqualTo(shop);
            });
            assertThat(kernel.committedEvents()).containsExactly(new RoleAssigned(roleId, clerk, mpcs, shop));
        }

        @Test
        void aTemplateMayBeAssignedDirectly() {
            assignRole.handle(new AssignRole(clerk, template, null), asAdmin());
            assertThat(kernel.committedEvents()).containsExactly(new RoleAssigned(template, clerk, mpcs, null));
        }

        @Test
        void anotherEntitysRoleIsNotFound() {
            UUID theirs = fx.role(otherMpcs, "prt.location.view");
            refused(() -> assignRole.handle(new AssignRole(clerk, theirs, null), asAdmin()), "m1.role.not_found");
        }

        @Test
        void aRoleWithNoOwnerThatIsNotATemplateIsNotTheEntitys() {
            UUID stray = Ids.next();
            superuserJdbc()
                    .update(
                            "insert into security.role (role_id, owner_entity_id, name_en, is_template) values (?, null, ?, false)",
                            stray,
                            SecurityFixture.NAME_PREFIX + stray);
            refused(
                    () -> assignRole.handle(new AssignRole(clerk, stray, null), asAdmin()),
                    "m1.assignment.role_not_in_scope");
        }

        @Test
        void aRetiredRoleIsNotAssigned() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            superuserJdbc().update("update security.role set status = 'RETIRED' where role_id = ?", roleId);
            refused(() -> assignRole.handle(new AssignRole(clerk, roleId, null), asAdmin()), "m1.role.retired");
        }

        @Test
        void aCallerAtALocationAssignsThereAlone() {
            UUID manager = fx.user(mpcs);
            fx.assign(manager, fx.role(mpcs, "gov.role.manage", "prt.location.view"), mpcs, shop);
            UUID roleId = fx.role(mpcs, "prt.location.view");
            ScopeContext atShop = ScopeContext.dev(manager, mpcs, shop);

            refused(
                    () -> assignRole.handle(new AssignRole(clerk, roleId, secondShop), atShop),
                    "m1.assignment.outside_caller_scope");
            refused(
                    () -> assignRole.handle(new AssignRole(clerk, roleId, null), atShop),
                    "m1.assignment.outside_caller_scope");

            assignRole.handle(new AssignRole(clerk, roleId, shop), atShop);
            assertThat(kernel.committedEvents()).containsExactly(new RoleAssigned(roleId, clerk, mpcs, shop));
        }

        @Test
        void aLocationOfAnotherEntityIsRefused() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            refused(
                    () -> assignRole.handle(new AssignRole(clerk, roleId, otherShop), asAdmin()),
                    "m1.assignment.location_not_in_entity");
        }

        @Test
        void aUserOfAnotherEntityIsRefused() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            UUID stranger = fx.user(otherMpcs);
            refused(
                    () -> assignRole.handle(new AssignRole(stranger, roleId, null), asAdmin()),
                    "m1.assignment.user_not_in_scope");
        }

        @Test
        void aDeactivatedUserIsRefused() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            UUID gone = fx.user(mpcs, "DEACTIVATED");
            refused(
                    () -> assignRole.handle(new AssignRole(gone, roleId, null), asAdmin()),
                    "m1.assignment.user_deactivated");
        }

        @Test
        void aReadOnlyClassIsNotAssignedByAnEntity() {
            UUID viewRole = fx.roleOfClass(mpcs, "FEDERATION_VIEW", "prt.location.view");
            UUID external = fx.roleOfClass(null, "EXTERNAL_TIMEBOXED", "prt.location.view");
            refused(
                    () -> assignRole.handle(new AssignRole(clerk, viewRole, null), asAdmin()),
                    "m1.assignment.class_not_permitted");
            refused(
                    () -> assignRole.handle(new AssignRole(clerk, external, null), asAdmin()),
                    "m1.assignment.class_not_permitted");
        }

        @Test
        void anAssignmentIsMadeOnce() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            fx.assign(clerk, roleId, mpcs, shop);
            refused(() -> assignRole.handle(new AssignRole(clerk, roleId, shop), asAdmin()), "m1.assignment.exists");
        }

        @Test
        void assigningIsGrantingSoTheGrantorMustHoldEveryPermission() {
            UUID roleId = fx.role(mpcs, "prt.location.view", "sys.device.manage");
            refused(
                    () -> assignRole.handle(new AssignRole(clerk, roleId, null), asAdmin()),
                    "m1.role.permission_not_held");
        }

        @Test
        void aHalfHeldAtAnotherShopCountsForAShopScopedGrantor() {
            // The clerk holds one half of a ROLE pair at the second shop, which a manager scoped
            // to the first shop cannot see under own_read; the entity-wide count still sees it.
            fx.pair(mpcs, "prt.location.view", "prt.position.manage", "ROLE");
            fx.assign(clerk, fx.role(mpcs, "prt.location.view"), mpcs, secondShop);
            // An entity-wide role of no consequence makes the clerk visible at the first shop
            // (m1security V0012: a shop sees its own staff and the entity-wide ones).
            fx.assign(clerk, fx.role(mpcs, "prt.relationship.view"), mpcs, null);
            UUID manager = fx.user(mpcs);
            fx.assign(manager, fx.role(mpcs, "gov.role.manage", "prt.position.manage"), mpcs, shop);
            UUID otherHalf = fx.role(mpcs, "prt.position.manage");

            refused(
                    () -> assignRole.handle(
                            new AssignRole(clerk, otherHalf, shop), ScopeContext.dev(manager, mpcs, shop)),
                    "m1.assignment.sod_conflict");
        }

        @Test
        void aPersonDoesNotComeToHoldBothHalvesOfARolePair() {
            fx.assign(clerk, fx.role(mpcs, "prt.location.view"), mpcs, null);
            fx.pair(mpcs, "prt.location.view", "prt.position.manage", "ROLE");
            UUID roleId = fx.role(mpcs, "prt.position.manage");
            refused(
                    () -> assignRole.handle(new AssignRole(clerk, roleId, shop), asAdmin()),
                    "m1.assignment.sod_conflict");
        }
    }

    @Nested
    class RevokingARole {

        @Test
        void theAssignmentIsDeletedAuditedAndPublished() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            fx.assign(clerk, roleId, mpcs, shop);

            revokeRole.handle(new RevokeRole(clerk, roleId, shop, "moved to head office"), asAdmin());

            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select count(*) from security.user_role where user_id = ?", Integer.class, clerk))
                    .isZero();
            assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
                assertThat(record.eventType()).isEqualTo("ROLE_REVOKED");
                assertThat(record.subject().id()).isEqualTo(clerk);
                assertThat(((Map<?, ?>) record.before()).get("roleId")).isEqualTo(roleId);
                assertThat(record.after()).isNull();
                assertThat(record.reason()).isEqualTo("moved to head office");
            });
            assertThat(kernel.committedEvents()).containsExactly(new RoleRevoked(roleId, clerk, mpcs, shop));
        }

        @Test
        void anAssignmentThatDoesNotExistIsNotFound() {
            UUID roleId = fx.role(mpcs, "prt.location.view");
            fx.assign(clerk, roleId, mpcs, shop);
            refused(
                    () -> revokeRole.handle(new RevokeRole(clerk, roleId, secondShop, null), asAdmin()),
                    "m1.assignment.not_found");
        }

        @Test
        void aCallerAtALocationRevokesThereAlone() {
            UUID manager = fx.user(mpcs);
            fx.assign(manager, fx.role(mpcs, "gov.role.manage"), mpcs, shop);
            UUID roleId = fx.role(mpcs, "prt.location.view");
            fx.assign(clerk, roleId, mpcs, null);
            refused(
                    () -> revokeRole.handle(
                            new RevokeRole(clerk, roleId, null, null), ScopeContext.dev(manager, mpcs, shop)),
                    "m1.assignment.outside_caller_scope");
        }

        @Test
        void twoRevocationsAtOnceLeaveOneUserManager() throws Exception {
            // Two administrators revoke each other's administrator role at the same moment. Each
            // would count two holders under READ COMMITTED; the per-entity lock makes the second
            // wait and count one. Exactly one revocation goes through, whichever it is.
            UUID deputy = fx.user(mpcs);
            fx.assign(deputy, adminRole, mpcs, null);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<String>> outcomes = new ArrayList<>();
                for (UUID[] pair : List.of(new UUID[] {admin, deputy}, new UUID[] {deputy, admin})) {
                    outcomes.add(pool.submit(() -> {
                        start.await();
                        try {
                            revokeRole.handle(
                                    new RevokeRole(pair[1], adminRole, null, null),
                                    ScopeContext.dev(pair[0], mpcs, null));
                            return "revoked";
                        } catch (ProblemException e) {
                            return e.messageId();
                        }
                    }));
                }
                start.countDown();
                List<String> results = new ArrayList<>();
                for (Future<String> outcome : outcomes) {
                    results.add(outcome.get(30, TimeUnit.SECONDS));
                }
                assertThat(results).containsExactlyInAnyOrder("revoked", "m1.assignment.last_user_manager");
            } finally {
                pool.shutdownNow();
            }
            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select count(*) from security.user_role where role_id = ?",
                                    Integer.class,
                                    adminRole))
                    .isEqualTo(1);
        }

        @Test
        void theLastUserManagerIsKeptAndASecondOneMayGo() {
            refused(
                    () -> revokeRole.handle(new RevokeRole(admin, adminRole, null, null), asAdmin()),
                    "m1.assignment.last_user_manager");

            UUID deputy = fx.user(mpcs);
            fx.assign(deputy, adminRole, mpcs, null);
            revokeRole.handle(new RevokeRole(deputy, adminRole, null, null), asAdmin());
            assertThat(kernel.committedEvents()).containsExactly(new RoleRevoked(adminRole, deputy, mpcs, null));
        }
    }

    @Nested
    class SeparationOfDutiesPairs {

        @Test
        void anEntityAddsAPairOfItsOwn() {
            UUID pairId =
                    setSodPair.handle(new SetSodPair("sys.device.view", "prt.location.manage", "INSTANCE"), asAdmin());

            Map<String, Object> row = superuserJdbc()
                    .queryForMap(
                            "select permission_a, permission_b, mode, owner_entity_id from security.sod_pair where sod_pair_id = ?",
                            pairId);
            assertThat(row)
                    .containsEntry("permission_a", "prt.location.manage")
                    .containsEntry("permission_b", "sys.device.view")
                    .containsEntry("mode", "INSTANCE")
                    .containsEntry("owner_entity_id", mpcs);
            assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
                assertThat(record.eventType()).isEqualTo("SOD_PAIR_CHANGED");
                assertThat(record.subject().id()).isEqualTo(pairId);
            });
            assertThat(kernel.committedEvents())
                    .containsExactly(
                            new SodPairChanged(pairId, "prt.location.manage", "sys.device.view", "INSTANCE", false));
        }

        @Test
        void anEntityRaisesAPairToRoleModeWhenNobodyHoldsBoth() {
            UUID pairId =
                    setSodPair.handle(new SetSodPair("prt.relationship.manage", "sys.device.view", "ROLE"), asAdmin());
            assertThat(kernel.committedEvents())
                    .containsExactly(
                            new SodPairChanged(pairId, "prt.relationship.manage", "sys.device.view", "ROLE", false));
        }

        @Test
        void raisingIsRefusedWhileSomebodyHoldsBoth() {
            // The administrator's role holds both halves of the seeded default gov.role.manage/gov.user.manage.
            refused(
                    () -> setSodPair.handle(new SetSodPair("gov.role.manage", "gov.user.manage", "ROLE"), asAdmin()),
                    "m1.sod.existing_conflict");
        }

        @Test
        void raisingIgnoresADeactivatedHolderOfBoth() {
            UUID departed = fx.user(mpcs, "DEACTIVATED");
            fx.assign(departed, fx.role(mpcs, "prt.relationship.view"), mpcs, null);
            fx.assign(departed, fx.role(mpcs, "prt.location.view"), mpcs, null);

            UUID pairId =
                    setSodPair.handle(new SetSodPair("prt.relationship.view", "prt.location.view", "ROLE"), asAdmin());

            assertThat(kernel.committedEvents())
                    .containsExactly(
                            new SodPairChanged(pairId, "prt.location.view", "prt.relationship.view", "ROLE", false));
        }

        @Test
        void aFederationRolePairIsNotLowered() {
            fx.pair(null, "prt.relationship.view", "sys.device.view", "ROLE");
            refused(
                    () -> setSodPair.handle(
                            new SetSodPair("prt.relationship.view", "sys.device.view", "INSTANCE"), asAdmin()),
                    "m1.sod.cannot_lower");
        }

        @Test
        void theRuleInForceAgainIsRefused() {
            refused(
                    () -> setSodPair.handle(
                            new SetSodPair("gov.user.manage", "gov.role.manage", "INSTANCE"), asAdmin()),
                    "m1.sod.unchanged");
        }

        @Test
        void theShapeOfAPairIsGuarded() {
            refused(
                    () -> setSodPair.handle(new SetSodPair("sys.device.view", "prt.location.view", "BOTH"), asAdmin()),
                    "m1.sod.mode_invalid");
            refused(
                    () -> setSodPair.handle(new SetSodPair("sys.device.view", "sys.device.view", "ROLE"), asAdmin()),
                    "m1.sod.same_permission");
            refused(
                    () -> setSodPair.handle(new SetSodPair("sys.device.view", "no.such.code", "ROLE"), asAdmin()),
                    "m1.sod.permission_unknown");
            refused(
                    () -> setSodPair.handle(
                            new SetSodPair("sys.device.view", "prt.location.view", "ROLE"),
                            ScopeContext.dev(admin, mpcs, shop)),
                    "m1.role.entity_scope_required");
        }

        @Test
        void anEntityRemovesItsOwnPairAndNeverAFederationDefault() {
            UUID own = fx.pair(mpcs, "prt.location.manage", "sys.device.view", "INSTANCE");
            UUID federationDefault = fx.pair(null, "prt.relationship.view", "sys.device.view", "INSTANCE");

            refused(() -> removeSodPair.handle(new RemoveSodPair(federationDefault), asAdmin()), "m1.sod.not_owner");
            refused(() -> removeSodPair.handle(new RemoveSodPair(Ids.next()), asAdmin()), "m1.sod.not_found");

            removeSodPair.handle(new RemoveSodPair(own), asAdmin());
            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select count(*) from security.sod_pair where sod_pair_id = ?", Integer.class, own))
                    .isZero();
            assertThat(kernel.committedEvents())
                    .containsExactly(
                            new SodPairChanged(own, "prt.location.manage", "sys.device.view", "INSTANCE", true));
        }
    }

    @Nested
    class RowLevelSecurity {

        @Test
        void theFederationAloneWritesTemplates() {
            assertThat(inScope(
                            admin,
                            mpcs,
                            () -> jdbc.update("update security.role set name_si = 'x' where role_id = ?", template)))
                    .isZero();
            assertThat(inScope(
                            federationAdmin,
                            federation,
                            () -> jdbc.update("update security.role set name_si = 'x' where role_id = ?", template)))
                    .isEqualTo(1);
        }

        @Test
        void anEntityDeletesNoOtherEntitysAssignmentsOrPermissions() {
            UUID theirs = fx.user(otherMpcs);
            UUID theirRole = fx.role(otherMpcs, "prt.location.view");
            fx.assign(theirs, theirRole, otherMpcs, null);

            assertThat(inScope(
                            admin, mpcs, () -> jdbc.update("delete from security.user_role where user_id = ?", theirs)))
                    .isZero();
            assertThat(inScope(
                            admin,
                            mpcs,
                            () -> jdbc.update("delete from security.role_permission where role_id = ?", theirRole)))
                    .isZero();
            assertThat(inScope(
                            admin,
                            mpcs,
                            () -> jdbc.update("delete from security.role_permission where role_id = ?", template)))
                    .isZero();
        }
    }

    /** The permission codes of an audited role state ({@code {"permissions": {code: limits}}}). */
    private static List<String> permissionKeys(Object state) {
        Map<?, ?> permissions = (Map<?, ?>) ((Map<?, ?>) state).get("permissions");
        return permissions.keySet().stream().map(String::valueOf).toList();
    }

    private <T> T inScope(UUID user, UUID entity, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', '', true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    user.toString(),
                    Ids.next().toString(),
                    entity.toString());
            T result = work.get();
            status.setRollbackOnly();
            return result;
        });
    }
}
