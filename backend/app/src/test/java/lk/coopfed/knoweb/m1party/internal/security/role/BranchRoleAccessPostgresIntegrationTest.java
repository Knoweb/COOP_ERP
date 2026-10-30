package lk.coopfed.knoweb.m1party.internal.security.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.security.UserScopes;
import lk.coopfed.knoweb.m1party.api.AssignRole;
import lk.coopfed.knoweb.m1party.api.RevokeRole;
import lk.coopfed.knoweb.m1party.api.RoleAssigned;
import lk.coopfed.knoweb.m1party.api.RoleRevoked;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polgolla Program Wave 2 (PGL-M3-04): Branch / Role User Access Profiles in M1 Security.
 *
 * <p>Proves explicitly that:
 * <ul>
 *   <li>Separate user accounts for shop managers and assistants enforce strict role boundaries.</li>
 *   <li>Shop Manager holds branch management, till session, void, count, GRN and write-off permissions at its assigned branch.</li>
 *   <li>Shop Assistant holds ONLY basic POS receipt issuing and MRP picking at its assigned branch, with all management actions denied.</li>
 *   <li>Marketing Manager access is modelled across approved main and sub-branches, while denied at unapproved branches and barred from inventory/till mutations.</li>
 *   <li>Stock Keeper inventory operations are strictly scoped to approved sub-branches, denying unapproved branch access and POS operations.</li>
 *   <li>Cross-branch and cross-tenant access is enforced at the database level by PostgreSQL Row-Level Security (RLS) with zero application-side tenant filtering.</li>
 *   <li>All role templates are registered with full trilingual descriptions (English, Sinhala, Tamil).</li>
 * </ul>
 */
class BranchRoleAccessPostgresIntegrationTest extends PostgresIntegrationTest {

    public static final UUID SHOP_MANAGER_TEMPLATE_ID = UUID.fromString("01999a10-2c4e-7f1a-8b3d-5e6f70819a21");
    public static final UUID SHOP_ASSISTANT_TEMPLATE_ID = UUID.fromString("01999a10-2c4e-7f1a-8b3d-5e6f70819a22");
    public static final UUID MARKETING_MANAGER_TEMPLATE_ID = UUID.fromString("01999a10-2c4e-7f1a-8b3d-5e6f70819a23");
    public static final UUID STOCK_KEEPER_TEMPLATE_ID = UUID.fromString("01999a10-2c4e-7f1a-8b3d-5e6f70819a24");

    private static final Set<String> SHOP_MANAGER_PERMISSIONS = Set.of(
            "prt.location.view",
            "cat.sku.view",
            "inv.stock.view",
            "shop.grn.confirm",
            "shop.count.record",
            "inv.writeoff.request",
            "pos.receipt.issue",
            "pos.mrp.pick",
            "pos.receipt.void",
            "pos.refund.same_session",
            "pos.negative_stock.acknowledge",
            "pos.session.manage",
            "pos.receipt.margin",
            "rpt.target.view",
            "rpt.report.run");

    private static final Set<String> SHOP_ASSISTANT_PERMISSIONS =
            Set.of("pos.receipt.issue", "pos.mrp.pick", "cat.sku.view", "inv.stock.view", "prt.location.view");

    private static final Set<String> MARKETING_MANAGER_PERMISSIONS = Set.of(
            "cat.sku.view",
            "prc.pricelist.view",
            "prc.review.view",
            "prc.label.print",
            "rpt.report.run",
            "rpt.target.view",
            "pos.receipt.margin",
            "prt.location.view");

    private static final Set<String> STOCK_KEEPER_PERMISSIONS = Set.of(
            "inv.stock.view",
            "cat.sku.view",
            "shop.count.record",
            "shop.grn.confirm",
            "inv.writeoff.request",
            "prt.location.view");

    private static final Set<String> ALL_ROLE_PERMISSIONS = Stream.of(
                    SHOP_MANAGER_PERMISSIONS,
                    SHOP_ASSISTANT_PERMISSIONS,
                    MARKETING_MANAGER_PERMISSIONS,
                    STOCK_KEEPER_PERMISSIONS)
            .flatMap(Set::stream)
            .collect(Collectors.toSet());

    @Autowired
    Handles<AssignRole, UUID> assignRole;

    @Autowired
    Handles<RevokeRole, UUID> revokeRole;

    @Autowired
    PermissionResolver resolver;

    @Autowired
    UserScopes userScopes;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    SecurityFixture fx;
    UUID mpcs;
    UUID otherMpcs;
    UUID mainBranch;
    UUID subBranch1;
    UUID subBranch2;
    UUID otherBranch;

    UUID admin;
    UUID adminRole;

    UUID shopManagerUser;
    UUID shopAssistantUser;
    UUID marketingManagerUser;
    UUID stockKeeperUser;

    @BeforeEach
    void arrange() {
        fx = new SecurityFixture(superuserJdbc());
        fx.clean();

        mpcs = fx.mpcs("MPCS Polgolla Central");
        otherMpcs = fx.mpcs("MPCS Wayamba Outpost");

        mainBranch = fx.shop(mpcs);
        subBranch1 = fx.shop(mpcs);
        subBranch2 = fx.shop(mpcs);
        otherBranch = fx.shop(otherMpcs);

        admin = fx.user(mpcs);
        List<String> adminPerms = new ArrayList<>(ALL_ROLE_PERMISSIONS);
        adminPerms.add("gov.role.manage");
        adminPerms.add("gov.user.manage");
        adminPerms.add("prt.location.register");
        adminPerms.add("prt.position.manage");
        adminRole = fx.role(mpcs, adminPerms.toArray(String[]::new));
        fx.assign(admin, adminRole, mpcs, null);

        shopManagerUser = fx.user(mpcs, "BACK_OFFICE", "ACTIVE");
        shopAssistantUser = fx.user(mpcs, "BOTH", "ACTIVE");
        marketingManagerUser = fx.user(mpcs, "BACK_OFFICE", "ACTIVE");
        stockKeeperUser = fx.user(mpcs, "BACK_OFFICE", "ACTIVE");

        kernel.reset();
    }

    @AfterEach
    void cleanUp() {
        fx.clean();
    }

    private ScopeContext asAdmin() {
        return ScopeContext.dev(admin, mpcs, null);
    }

    private ScopeContext scope(UUID userId, UUID entityId, UUID locationId) {
        return ScopeContext.dev(userId, entityId, locationId);
    }

    private <T> T inScope(ScopeContext scope, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', ?, true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    scope.userId() == null ? "" : scope.userId().toString(),
                    Ids.next().toString(),
                    scope.entityId() == null ? "" : scope.entityId().toString(),
                    scope.locationId() == null ? "" : scope.locationId().toString());
            return work.get();
        });
    }

    private Set<String> resolveInScope(ScopeContext ctx) {
        return inScope(ctx, () -> resolver.resolve(ctx));
    }

    private boolean allowsInScope(ScopeContext ctx, String permission) {
        return inScope(ctx, () -> resolver.allows(ctx, permission));
    }

    private <T> T inScopeAt(UUID entityId, UUID locationId, String policyClass, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', ?, true),"
                            + " set_config('app.scope_class', ?, true), set_config('app.granted_entities', '{}', true)",
                    Ids.next().toString(),
                    Ids.next().toString(),
                    entityId.toString(),
                    locationId == null ? "" : locationId.toString(),
                    policyClass);
            try {
                return work.get();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    @Nested
    @DisplayName("Role Template Definitions and Trilingual Descriptions")
    class TemplateDefinitions {

        @Test
        @DisplayName("All four branch/role templates exist with full trilingual descriptions and exact permissions")
        void verifyTemplateDefinitions() {
            JdbcTemplate db = superuserJdbc();

            Map<String, Object> managerRow = db.queryForMap(
                    "select name_en, name_si, name_ta, role_class, is_template from security.role where role_id = ?",
                    SHOP_MANAGER_TEMPLATE_ID);
            assertThat(managerRow.get("name_en")).isEqualTo("Shop Manager");
            assertThat(managerRow.get("name_si")).isEqualTo("වෙළඳසැල් කළමනාකරු");
            assertThat(managerRow.get("name_ta")).isEqualTo("கடை முகாமையாளர்");
            assertThat(managerRow.get("role_class")).isEqualTo("OWN");
            assertThat(managerRow.get("is_template")).isEqualTo(true);

            List<String> managerPerms = db.queryForList(
                    "select permission_code from security.role_permission where role_id = ?",
                    String.class,
                    SHOP_MANAGER_TEMPLATE_ID);
            assertThat(managerPerms).containsExactlyInAnyOrderElementsOf(SHOP_MANAGER_PERMISSIONS);

            Map<String, Object> assistantRow = db.queryForMap(
                    "select name_en, name_si, name_ta, role_class, is_template from security.role where role_id = ?",
                    SHOP_ASSISTANT_TEMPLATE_ID);
            assertThat(assistantRow.get("name_en")).isEqualTo("Shop Assistant");
            assertThat(assistantRow.get("name_si")).isEqualTo("වෙළඳසැල් සහකාර");
            assertThat(assistantRow.get("name_ta")).isEqualTo("கடை உதவியாளர்");
            assertThat(assistantRow.get("role_class")).isEqualTo("OWN");
            assertThat(assistantRow.get("is_template")).isEqualTo(true);

            List<String> assistantPerms = db.queryForList(
                    "select permission_code from security.role_permission where role_id = ?",
                    String.class,
                    SHOP_ASSISTANT_TEMPLATE_ID);
            assertThat(assistantPerms).containsExactlyInAnyOrderElementsOf(SHOP_ASSISTANT_PERMISSIONS);

            Map<String, Object> marketingRow = db.queryForMap(
                    "select name_en, name_si, name_ta, role_class, is_template from security.role where role_id = ?",
                    MARKETING_MANAGER_TEMPLATE_ID);
            assertThat(marketingRow.get("name_en")).isEqualTo("Marketing Manager");
            assertThat(marketingRow.get("name_si")).isEqualTo("අලෙවි කළමනාකරු");
            assertThat(marketingRow.get("name_ta")).isEqualTo("சந்தைப்படுத்தல் முகாமையாளர்");
            assertThat(marketingRow.get("role_class")).isEqualTo("OWN");
            assertThat(marketingRow.get("is_template")).isEqualTo(true);

            List<String> marketingPerms = db.queryForList(
                    "select permission_code from security.role_permission where role_id = ?",
                    String.class,
                    MARKETING_MANAGER_TEMPLATE_ID);
            assertThat(marketingPerms).containsExactlyInAnyOrderElementsOf(MARKETING_MANAGER_PERMISSIONS);

            Map<String, Object> stockKeeperRow = db.queryForMap(
                    "select name_en, name_si, name_ta, role_class, is_template from security.role where role_id = ?",
                    STOCK_KEEPER_TEMPLATE_ID);
            assertThat(stockKeeperRow.get("name_en")).isEqualTo("Stock Keeper");
            assertThat(stockKeeperRow.get("name_si")).isEqualTo("ගබඩා භාරකරු");
            assertThat(stockKeeperRow.get("name_ta")).isEqualTo("களஞ்சියப் பொறுப்பாளர்");
            assertThat(stockKeeperRow.get("role_class")).isEqualTo("OWN");
            assertThat(stockKeeperRow.get("is_template")).isEqualTo(true);

            List<String> stockKeeperPerms = db.queryForList(
                    "select permission_code from security.role_permission where role_id = ?",
                    String.class,
                    STOCK_KEEPER_TEMPLATE_ID);
            assertThat(stockKeeperPerms).containsExactlyInAnyOrderElementsOf(STOCK_KEEPER_PERMISSIONS);
        }
    }

    @Nested
    @DisplayName("Shop Manager and Assistant Role Separation")
    class ShopManagerAndAssistantProfiles {

        @Test
        @DisplayName(
                "Shop Manager and Shop Assistant have separate accounts and segregated permissions at approved branch")
        void separateManagerAndAssistantAccounts() {
            assignRole.handle(new AssignRole(shopManagerUser, SHOP_MANAGER_TEMPLATE_ID, mainBranch), asAdmin());
            assignRole.handle(new AssignRole(shopAssistantUser, SHOP_ASSISTANT_TEMPLATE_ID, mainBranch), asAdmin());

            assertThat(kernel.committedAudit())
                    .filteredOn(a -> "ROLE_ASSIGNED".equals(a.eventType()))
                    .hasSize(2);
            assertThat(kernel.committedEvents())
                    .filteredOn(e -> e instanceof RoleAssigned)
                    .hasSize(2);

            ScopeContext managerMainScope = scope(shopManagerUser, mpcs, mainBranch);
            Set<String> managerResolved = resolveInScope(managerMainScope);

            assertThat(managerResolved).containsAll(SHOP_MANAGER_PERMISSIONS);
            assertThat(allowsInScope(managerMainScope, "pos.session.manage")).isTrue();
            assertThat(allowsInScope(managerMainScope, "pos.receipt.void")).isTrue();
            assertThat(allowsInScope(managerMainScope, "pos.refund.same_session"))
                    .isTrue();
            assertThat(allowsInScope(managerMainScope, "shop.grn.confirm")).isTrue();
            assertThat(allowsInScope(managerMainScope, "shop.count.record")).isTrue();
            assertThat(allowsInScope(managerMainScope, "inv.writeoff.request")).isTrue();
            assertThat(allowsInScope(managerMainScope, "pos.receipt.margin")).isTrue();
            assertThat(allowsInScope(managerMainScope, "rpt.target.view")).isTrue();
            assertThat(allowsInScope(managerMainScope, "rpt.report.run")).isTrue();

            ScopeContext assistantMainScope = scope(shopAssistantUser, mpcs, mainBranch);
            Set<String> assistantResolved = resolveInScope(assistantMainScope);

            assertThat(assistantResolved).containsExactlyInAnyOrderElementsOf(SHOP_ASSISTANT_PERMISSIONS);
            assertThat(allowsInScope(assistantMainScope, "pos.receipt.issue")).isTrue();
            assertThat(allowsInScope(assistantMainScope, "pos.mrp.pick")).isTrue();
            assertThat(allowsInScope(assistantMainScope, "cat.sku.view")).isTrue();
            assertThat(allowsInScope(assistantMainScope, "inv.stock.view")).isTrue();

            assertThat(allowsInScope(assistantMainScope, "pos.session.manage"))
                    .as("Shop Assistant cannot manage till sessions")
                    .isFalse();
            assertThat(allowsInScope(assistantMainScope, "pos.receipt.void"))
                    .as("Shop Assistant cannot void receipts")
                    .isFalse();
            assertThat(allowsInScope(assistantMainScope, "pos.refund.same_session"))
                    .as("Shop Assistant cannot refund receipts")
                    .isFalse();
            assertThat(allowsInScope(assistantMainScope, "shop.grn.confirm"))
                    .as("Shop Assistant cannot confirm shop GRN")
                    .isFalse();
            assertThat(allowsInScope(assistantMainScope, "shop.count.record"))
                    .as("Shop Assistant cannot record stock counts")
                    .isFalse();
            assertThat(allowsInScope(assistantMainScope, "inv.writeoff.request"))
                    .as("Shop Assistant cannot request inventory write-offs")
                    .isFalse();
            assertThat(allowsInScope(assistantMainScope, "pos.receipt.margin"))
                    .as("Shop Assistant cannot view margin on receipts")
                    .isFalse();
            assertThat(allowsInScope(assistantMainScope, "rpt.target.view"))
                    .as("Shop Assistant cannot view sales targets")
                    .isFalse();
            assertThat(allowsInScope(assistantMainScope, "rpt.report.run"))
                    .as("Shop Assistant cannot run reports")
                    .isFalse();

            ScopeContext managerSubScope = scope(shopManagerUser, mpcs, subBranch1);
            assertThat(resolveInScope(managerSubScope))
                    .as("Shop Manager has zero permissions at unapproved sub-branch")
                    .isEmpty();
            assertThat(allowsInScope(managerSubScope, "shop.grn.confirm")).isFalse();
            assertThat(allowsInScope(managerSubScope, "pos.session.manage")).isFalse();

            ScopeContext assistantSubScope = scope(shopAssistantUser, mpcs, subBranch1);
            assertThat(resolveInScope(assistantSubScope))
                    .as("Shop Assistant has zero permissions at unapproved sub-branch")
                    .isEmpty();
            assertThat(allowsInScope(assistantSubScope, "pos.receipt.issue")).isFalse();
        }
    }

    @Nested
    @DisplayName("Marketing Manager Multi-Branch Access Profiles")
    class MarketingManagerProfiles {

        @Test
        @DisplayName(
                "Marketing Manager access is permitted across approved main and sub branches, denied at unapproved branches")
        void multiBranchMarketingAccess() {
            assignRole.handle(
                    new AssignRole(marketingManagerUser, MARKETING_MANAGER_TEMPLATE_ID, mainBranch), asAdmin());
            assignRole.handle(
                    new AssignRole(marketingManagerUser, MARKETING_MANAGER_TEMPLATE_ID, subBranch1), asAdmin());

            Set<Scope> grantedScopes = userScopes.scopesOf(marketingManagerUser);
            assertThat(grantedScopes)
                    .containsExactlyInAnyOrder(new Scope(mpcs, mainBranch), new Scope(mpcs, subBranch1));
            assertThat(grantedScopes).doesNotContain(new Scope(mpcs, subBranch2));

            ScopeContext mainScope = scope(marketingManagerUser, mpcs, mainBranch);
            assertThat(resolveInScope(mainScope)).containsAll(MARKETING_MANAGER_PERMISSIONS);
            assertThat(allowsInScope(mainScope, "prc.pricelist.view")).isTrue();
            assertThat(allowsInScope(mainScope, "prc.review.view")).isTrue();
            assertThat(allowsInScope(mainScope, "prc.label.print")).isTrue();
            assertThat(allowsInScope(mainScope, "rpt.report.run")).isTrue();
            assertThat(allowsInScope(mainScope, "rpt.target.view")).isTrue();
            assertThat(allowsInScope(mainScope, "pos.receipt.margin")).isTrue();
            assertThat(allowsInScope(mainScope, "cat.sku.view")).isTrue();

            ScopeContext sub1Scope = scope(marketingManagerUser, mpcs, subBranch1);
            assertThat(resolveInScope(sub1Scope)).containsAll(MARKETING_MANAGER_PERMISSIONS);
            assertThat(allowsInScope(sub1Scope, "prc.pricelist.view")).isTrue();
            assertThat(allowsInScope(sub1Scope, "prc.label.print")).isTrue();
            assertThat(allowsInScope(sub1Scope, "rpt.report.run")).isTrue();
            assertThat(allowsInScope(sub1Scope, "rpt.target.view")).isTrue();
            assertThat(allowsInScope(sub1Scope, "pos.receipt.margin")).isTrue();

            ScopeContext unapprovedScope = scope(marketingManagerUser, mpcs, subBranch2);
            assertThat(resolveInScope(unapprovedScope))
                    .as("Marketing Manager has no access at unapproved sub-branch 2")
                    .isEmpty();
            assertThat(allowsInScope(unapprovedScope, "prc.pricelist.view")).isFalse();
            assertThat(allowsInScope(unapprovedScope, "rpt.report.run")).isFalse();
            assertThat(allowsInScope(unapprovedScope, "pos.receipt.margin")).isFalse();

            for (ScopeContext activeScope : List.of(mainScope, sub1Scope)) {
                assertThat(allowsInScope(activeScope, "shop.count.record"))
                        .as("Marketing Manager cannot record stock counts")
                        .isFalse();
                assertThat(allowsInScope(activeScope, "shop.grn.confirm"))
                        .as("Marketing Manager cannot confirm GRNs")
                        .isFalse();
                assertThat(allowsInScope(activeScope, "inv.writeoff.request"))
                        .as("Marketing Manager cannot request stock write-offs")
                        .isFalse();
                assertThat(allowsInScope(activeScope, "pos.receipt.issue"))
                        .as("Marketing Manager cannot issue till sale receipts")
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Stock Keeper Sub-Branch Inventory Operations")
    class StockKeeperProfiles {

        @Test
        @DisplayName("Stock Keeper permissions are strictly confined to approved sub-branch inventory operations")
        void subBranchInventoryOperationsOnly() {
            assignRole.handle(new AssignRole(stockKeeperUser, STOCK_KEEPER_TEMPLATE_ID, subBranch1), asAdmin());

            Set<Scope> scopes = userScopes.scopesOf(stockKeeperUser);
            assertThat(scopes).containsExactly(new Scope(mpcs, subBranch1));

            ScopeContext approvedSubScope = scope(stockKeeperUser, mpcs, subBranch1);
            Set<String> subPermissions = resolveInScope(approvedSubScope);

            assertThat(subPermissions).containsExactlyInAnyOrderElementsOf(STOCK_KEEPER_PERMISSIONS);
            assertThat(allowsInScope(approvedSubScope, "inv.stock.view")).isTrue();
            assertThat(allowsInScope(approvedSubScope, "shop.count.record")).isTrue();
            assertThat(allowsInScope(approvedSubScope, "shop.grn.confirm")).isTrue();
            assertThat(allowsInScope(approvedSubScope, "inv.writeoff.request")).isTrue();
            assertThat(allowsInScope(approvedSubScope, "prt.location.view")).isTrue();
            assertThat(allowsInScope(approvedSubScope, "cat.sku.view")).isTrue();

            assertThat(allowsInScope(approvedSubScope, "pos.receipt.issue"))
                    .as("Stock Keeper cannot issue sale receipts")
                    .isFalse();
            assertThat(allowsInScope(approvedSubScope, "pos.session.manage"))
                    .as("Stock Keeper cannot manage POS sessions")
                    .isFalse();
            assertThat(allowsInScope(approvedSubScope, "pos.receipt.void"))
                    .as("Stock Keeper cannot void receipts")
                    .isFalse();
            assertThat(allowsInScope(approvedSubScope, "prc.label.print"))
                    .as("Stock Keeper cannot print price labels")
                    .isFalse();
            assertThat(allowsInScope(approvedSubScope, "rpt.target.manage"))
                    .as("Stock Keeper cannot manage sales targets")
                    .isFalse();

            ScopeContext mainBranchScope = scope(stockKeeperUser, mpcs, mainBranch);
            assertThat(resolveInScope(mainBranchScope))
                    .as("Stock Keeper is denied at unapproved main branch")
                    .isEmpty();
            assertThat(allowsInScope(mainBranchScope, "shop.count.record")).isFalse();
            assertThat(allowsInScope(mainBranchScope, "shop.grn.confirm")).isFalse();
            assertThat(allowsInScope(mainBranchScope, "inv.writeoff.request")).isFalse();

            ScopeContext unapprovedSubScope = scope(stockKeeperUser, mpcs, subBranch2);
            assertThat(resolveInScope(unapprovedSubScope))
                    .as("Stock Keeper is denied at unapproved sub branch 2")
                    .isEmpty();
            assertThat(allowsInScope(unapprovedSubScope, "shop.count.record")).isFalse();
        }
    }

    @Nested
    @DisplayName("Database-Level Row-Level Security (RLS) Enforcement")
    class DatabaseRlsEnforcement {

        @Test
        @DisplayName("Database RLS restricts row visibility and denies writes across unapproved branches and tenants")
        void unapprovedBranchDeniedByDatabaseRls() {
            JdbcTemplate adminDb = superuserJdbc();

            UUID posMain = Ids.next();
            UUID posSub1 = Ids.next();
            UUID posOtherEntity = Ids.next();

            adminDb.update(
                    "insert into party.till_position (till_position_id, location_id, position_no, status, owner_entity_id)"
                            + " values (?, ?, 1, 'ACTIVE', ?)",
                    posMain,
                    mainBranch,
                    mpcs);
            adminDb.update(
                    "insert into party.till_position (till_position_id, location_id, position_no, status, owner_entity_id)"
                            + " values (?, ?, 1, 'ACTIVE', ?)",
                    posSub1,
                    subBranch1,
                    mpcs);
            adminDb.update(
                    "insert into party.till_position (till_position_id, location_id, position_no, status, owner_entity_id)"
                            + " values (?, ?, 1, 'ACTIVE', ?)",
                    posOtherEntity,
                    otherBranch,
                    otherMpcs);

            List<UUID> visiblePositionsAtSub1 = inScopeAt(
                    mpcs,
                    subBranch1,
                    "OWN",
                    () -> jdbc.queryForList("select till_position_id from party.till_position", UUID.class));

            assertThat(visiblePositionsAtSub1)
                    .as("RLS allows viewing only the till positions of the caller's active location")
                    .containsExactly(posSub1)
                    .doesNotContain(posMain, posOtherEntity);

            List<UUID> visibleLocationsAtSub1 = inScopeAt(
                    mpcs,
                    subBranch1,
                    "OWN",
                    () -> jdbc.queryForList("select location_id from party.location", UUID.class));

            assertThat(visibleLocationsAtSub1)
                    .as("RLS allows viewing only the active branch location under OWN policy")
                    .containsExactly(subBranch1)
                    .doesNotContain(mainBranch, subBranch2, otherBranch);

            inScopeAt(mpcs, subBranch1, "OWN", () -> {
                UUID newPosId = Ids.next();
                int inserted = jdbc.update(
                        "insert into party.till_position (till_position_id, location_id, position_no, status, owner_entity_id)"
                                + " values (?, ?, 2, 'ACTIVE', ?)",
                        newPosId,
                        subBranch1,
                        mpcs);
                assertThat(inserted).isEqualTo(1);
                return null;
            });

            assertThatThrownBy(() -> inScopeAt(mpcs, subBranch1, "OWN", () -> {
                        UUID unauthorizedPos = Ids.next();
                        return jdbc.update(
                                "insert into party.till_position (till_position_id, location_id, position_no, status, owner_entity_id)"
                                        + " values (?, ?, 2, 'ACTIVE', ?)",
                                unauthorizedPos,
                                mainBranch,
                                mpcs);
                    }))
                    .as("PostgreSQL RLS WITH CHECK policy refuses insertion at unapproved location")
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("row-level security");

            assertThatThrownBy(() -> inScopeAt(mpcs, subBranch1, "OWN", () -> {
                        UUID crossTenantPos = Ids.next();
                        return jdbc.update(
                                "insert into party.till_position (till_position_id, location_id, position_no, status, owner_entity_id)"
                                        + " values (?, ?, 2, 'ACTIVE', ?)",
                                crossTenantPos,
                                otherBranch,
                                otherMpcs);
                    }))
                    .as("PostgreSQL RLS refuses insertion across tenant entity boundaries")
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("row-level security");

            adminDb.update(
                    "delete from party.till_position where till_position_id in (?, ?, ?)",
                    posMain,
                    posSub1,
                    posOtherEntity);
        }
    }

    @Nested
    @DisplayName("Role Assignment Guards and Audit Verification")
    class RoleAssignmentGuardsAndAudit {

        @Test
        @DisplayName("AssignRole refuses unowned locations and assigns only within caller scope")
        void assignmentGuards() {
            assertThatThrownBy(() -> assignRole.handle(
                            new AssignRole(shopManagerUser, SHOP_MANAGER_TEMPLATE_ID, otherBranch), asAdmin()))
                    .isInstanceOf(ProblemException.class)
                    .satisfies(e -> assertThat(((ProblemException) e).messageId())
                            .isEqualTo("m1.assignment.location_not_in_entity"));

            ScopeContext locationScopedAdmin = scope(admin, mpcs, mainBranch);
            assertThatThrownBy(() -> assignRole.handle(
                            new AssignRole(stockKeeperUser, STOCK_KEEPER_TEMPLATE_ID, subBranch1), locationScopedAdmin))
                    .isInstanceOf(ProblemException.class)
                    .satisfies(e -> assertThat(((ProblemException) e).messageId())
                            .isEqualTo("m1.assignment.outside_caller_scope"));

            kernel.reset();
            assignRole.handle(new AssignRole(stockKeeperUser, STOCK_KEEPER_TEMPLATE_ID, subBranch1), asAdmin());

            assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
                assertThat(record.eventType()).isEqualTo("ROLE_ASSIGNED");
                assertThat(record.subject().type()).isEqualTo("user");
                assertThat(record.subject().id()).isEqualTo(stockKeeperUser);
                assertThat(((Map<?, ?>) record.after()).get("roleId")).isEqualTo(STOCK_KEEPER_TEMPLATE_ID);
                assertThat(((Map<?, ?>) record.after()).get("scopeLocationId")).isEqualTo(subBranch1);
            });
            assertThat(kernel.committedEvents()).singleElement().satisfies(event -> {
                assertThat(event).isInstanceOf(RoleAssigned.class);
                RoleAssigned ra = (RoleAssigned) event;
                assertThat(ra.roleId()).isEqualTo(STOCK_KEEPER_TEMPLATE_ID);
                assertThat(ra.userId()).isEqualTo(stockKeeperUser);
                assertThat(ra.scopeLocationId()).isEqualTo(subBranch1);
            });

            kernel.reset();
            revokeRole.handle(
                    new RevokeRole(stockKeeperUser, STOCK_KEEPER_TEMPLATE_ID, subBranch1, "Role revoked"), asAdmin());

            assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
                assertThat(record.eventType()).isEqualTo("ROLE_REVOKED");
                assertThat(record.subject().id()).isEqualTo(stockKeeperUser);
            });
            assertThat(kernel.committedEvents()).singleElement().satisfies(event -> {
                assertThat(event).isInstanceOf(RoleRevoked.class);
                RoleRevoked rr = (RoleRevoked) event;
                assertThat(rr.roleId()).isEqualTo(STOCK_KEEPER_TEMPLATE_ID);
                assertThat(rr.userId()).isEqualTo(stockKeeperUser);
                assertThat(rr.scopeLocationId()).isEqualTo(subBranch1);
            });
        }
    }
}
