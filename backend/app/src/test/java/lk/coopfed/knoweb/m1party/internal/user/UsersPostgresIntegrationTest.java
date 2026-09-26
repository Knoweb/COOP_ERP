package lk.coopfed.knoweb.m1party.internal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PinHasher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.CreateUser;
import lk.coopfed.knoweb.m1party.api.CredentialResetResult;
import lk.coopfed.knoweb.m1party.api.DeactivateUser;
import lk.coopfed.knoweb.m1party.api.ResetCredential;
import lk.coopfed.knoweb.m1party.api.UpdateUser;
import lk.coopfed.knoweb.m1party.api.UserActivated;
import lk.coopfed.knoweb.m1party.api.UserCreated;
import lk.coopfed.knoweb.m1party.api.UserCredentialReset;
import lk.coopfed.knoweb.m1party.api.UserDeactivated;
import lk.coopfed.knoweb.m1party.api.UserUpdated;
import lk.coopfed.knoweb.m1party.query.UserFilter;
import lk.coopfed.knoweb.m1party.query.UserQueries;
import lk.coopfed.knoweb.m1party.query.UserView;
import lk.coopfed.knoweb.testsupport.KernelRecorder.AuditRecord;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The four user commands of M1-07 against PostgreSQL, with an identity provider that records
 * what it was asked (the real one: {@link UsersAgainstTheProviderIntegrationTest}). Every guard
 * of 21A section 6 fails with its message id and commits nothing; every success commits the row,
 * its audit record and its event; no PIN, hash or password reaches either. The row-level
 * security of a shop-scoped caller over users (m1security V0012) is proved here too.
 */
@Import(UsersPostgresIntegrationTest.FakeProvider.class)
@SuppressWarnings("unchecked")
class UsersPostgresIntegrationTest extends PostgresIntegrationTest {

    static final UUID ENTITY = UUID.fromString("0190a707-0000-7000-8000-000000000001");
    static final UUID OTHER_ENTITY = UUID.fromString("0190a707-0000-7000-8000-000000000002");
    static final UUID STRICT_ENTITY = UUID.fromString("0190a707-0000-7000-8000-000000000003");
    static final UUID SHOP_A = UUID.fromString("0190a707-0000-7000-8000-00000000000a");
    static final UUID SHOP_B = UUID.fromString("0190a707-0000-7000-8000-00000000000b");
    static final UUID ADMIN = UUID.fromString("0190a707-0000-7000-8000-0000000000ad");

    static final List<UUID> ENTITIES = List.of(ENTITY, OTHER_ENTITY, STRICT_ENTITY);

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeProvider {
        @Bean
        @Primary
        RecordingIdentityProvider recordingIdentityProvider() {
            return new RecordingIdentityProvider();
        }
    }

    @Autowired
    RecordingIdentityProvider provider;

    @Autowired
    Handles<CreateUser, UUID> createUser;

    @Autowired
    Handles<UpdateUser, UUID> updateUser;

    @Autowired
    Handles<ResetCredential, CredentialResetResult> resetCredential;

    @Autowired
    Handles<DeactivateUser, UUID> deactivateUser;

    @Autowired
    UserQueries queries;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    TestRestTemplate http;

    private final ScopeContext admin = ScopeContext.dev(ADMIN, ENTITY, null);

    @BeforeEach
    void cleanSlate() {
        clean();
        provider.reset();
    }

    @AfterEach
    void tidyUp() {
        clean();
    }

    static void clean() {
        JdbcTemplate db = superuserJdbc();
        for (UUID entity : ENTITIES) {
            db.update("delete from security.user_role where scope_entity_id = ?", entity);
            db.update(
                    "delete from security.role_permission where role_id in (select role_id from security.role where owner_entity_id = ?)",
                    entity);
            db.update("delete from security.role where owner_entity_id = ?", entity);
            db.update("delete from security.app_user where home_entity_id = ?", entity);
            db.update("delete from party.entity_party_directory where entity_id = ?", entity);
            db.update("delete from party.entity where entity_id = ?", entity);
            db.update("delete from kernel.config_value where scope_entity_id = ?", entity);
        }
    }

    // ---- CreateUser ----

    @Test
    void createUserOpensTheLoginAndStoresTheSubject() {
        UUID userId = createUser.handle(
                new CreateUser(null, "Kamal.Perera", " Kamal Perera ", "si", "BACK_OFFICE", null), admin);

        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        "select username, display_name, language, user_kind, status, provider_subject, pin_hash"
                                + " from security.app_user where user_id = ?",
                        userId);
        assertThat(row)
                .containsEntry("username", "kamal.perera")
                .containsEntry("display_name", "Kamal Perera")
                .containsEntry("language", "si")
                .containsEntry("user_kind", "BACK_OFFICE")
                .containsEntry("status", "PENDING")
                .containsEntry("provider_subject", "subject-" + userId);
        assertThat(row.get("pin_hash")).isNull();
        assertThat(provider.methodsFor("subject-" + userId)).containsExactly("createUser");

        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo("USER_CREATED");
            assertThat(record.subject().type()).isEqualTo("user");
            assertThat(record.subject().id()).isEqualTo(userId);
            assertThat(record.before()).isNull();
            assertThat((Map<String, Object>) record.after())
                    .containsEntry("status", "PENDING")
                    .containsEntry("loginLinked", true)
                    .containsEntry("pinSet", false);
        });
        assertThat(kernel.committedEvents())
                .containsExactly(new UserCreated(userId, userId, ENTITY, "BACK_OFFICE", "PENDING"));
    }

    @Test
    void createUserGuardsFailWithTheirMessageAndCommitNothing() {
        superuserJdbc()
                .update(
                        "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind)"
                                + " values (?, ?, 'taken.elsewhere', 'Someone else', 'TILL')",
                        UUID.randomUUID(),
                        OTHER_ENTITY);
        UUID active = insertUser(ENTITY, "still.here", "BACK_OFFICE", "ACTIVE");

        refused(
                "m1.user.entity_scope_required",
                () -> createUser.handle(
                        new CreateUser(null, "at.a.shop", "Shop", "en", "TILL", null),
                        ScopeContext.dev(ADMIN, ENTITY, SHOP_A)));
        refused(
                "m1.user.home_entity_out_of_scope",
                () -> createUser.handle(
                        new CreateUser(OTHER_ENTITY, "elsewhere", "Elsewhere", "en", "TILL", null), admin));
        refused(
                "m1.user.kind_invalid",
                () -> createUser.handle(new CreateUser(null, "odd.kind", "Odd", "en", "MANAGER", null), admin));
        refused(
                "m1.user.external_federation_only",
                () -> createUser.handle(new CreateUser(null, "regulator", "Regulator", "en", "EXTERNAL", null), admin));
        refused(
                "m1.user.language_invalid",
                () -> createUser.handle(new CreateUser(null, "german", "German", "de", "TILL", null), admin));
        refused(
                "m1.user.username_invalid",
                () -> createUser.handle(new CreateUser(null, "a b", "Spaces", "en", "TILL", null), admin));
        // Taken by a user of another entity, which this administrator cannot even see.
        refused(
                "m1.user.username_taken",
                () -> createUser.handle(new CreateUser(null, "Taken.Elsewhere", "Clash", "en", "TILL", null), admin));
        refused(
                "m1.user.display_name_required",
                () -> createUser.handle(new CreateUser(null, "no.name", "  ", "en", "TILL", null), admin));
        refused(
                "m1.user.succeeds_invalid",
                () -> createUser.handle(new CreateUser(null, "successor", "Successor", "en", "TILL", active), admin));

        assertThat(provider.calls).isEmpty();
        assertThat(count("select count(*) from security.app_user where home_entity_id = ?", ENTITY))
                .isEqualTo(1);
    }

    @Test
    void returningStaffAreANewUserLinkedToTheDeactivatedOne() {
        UUID earlier = insertUser(ENTITY, "old.hand", "TILL", "DEACTIVATED");

        UUID userId = createUser.handle(new CreateUser(null, "old.hand2", "Old Hand", "ta", "TILL", earlier), admin);

        assertThat(queries.getUser(userId, admin))
                .get()
                .extracting(UserView::succeedsUserId)
                .isEqualTo(earlier);
    }

    // ---- UpdateUser ----

    @Test
    void updateUserChangesTheDetailsAndATillLeaverLosesThePin() {
        UUID userId = insertUser(ENTITY, "cashier.one", "BOTH", "ACTIVE");
        resetCredential.handle(new ResetCredential(userId, "PIN", "2580"), admin);
        kernel.reset();

        updateUser.handle(new UpdateUser(userId, "Cashier One", "ta", "BACK_OFFICE"), admin);

        UserView view = queries.getUser(userId, admin).orElseThrow();
        assertThat(view.displayName()).isEqualTo("Cashier One");
        assertThat(view.language()).isEqualTo("ta");
        assertThat(view.userKind()).isEqualTo("BACK_OFFICE");
        assertThat(view.pinSet()).isFalse();
        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo("USER_UPDATED");
            assertThat((Map<String, Object>) record.before()).containsEntry("pinSet", true);
            assertThat((Map<String, Object>) record.after()).containsEntry("pinSet", false);
        });
        assertThat(kernel.committedEvents())
                .containsExactly(new UserUpdated(userId, userId, ENTITY, "BACK_OFFICE", "ACTIVE"));
    }

    @Test
    void updateUserGuards() {
        UUID external = insertUser(ENTITY, "an.auditor", "EXTERNAL", "ACTIVE");
        UUID gone = insertUser(ENTITY, "gone.away", "TILL", "DEACTIVATED");
        UUID elsewhere = insertUser(OTHER_ENTITY, "not.mine", "TILL", "ACTIVE");
        UUID till = insertUser(ENTITY, "till.only", "TILL", "ACTIVE");

        refused("m1.user.not_found", () -> updateUser.handle(new UpdateUser(elsewhere, "X", "en", "TILL"), admin));
        refused("m1.user.deactivated", () -> updateUser.handle(new UpdateUser(gone, "X", "en", "TILL"), admin));
        refused(
                "m1.user.display_name_required",
                () -> updateUser.handle(new UpdateUser(till, "", "en", "TILL"), admin));
        refused("m1.user.language_invalid", () -> updateUser.handle(new UpdateUser(till, "X", "fr", "TILL"), admin));
        refused("m1.user.kind_invalid", () -> updateUser.handle(new UpdateUser(till, "X", "en", "BOSS"), admin));
        refused(
                "m1.user.kind_change_invalid",
                () -> updateUser.handle(new UpdateUser(till, "X", "en", "EXTERNAL"), admin));
        refused(
                "m1.user.kind_change_invalid",
                () -> updateUser.handle(new UpdateUser(external, "X", "en", "BOTH"), admin));
    }

    // ---- ResetCredential ----

    @Test
    void aTemporaryPasswordActivatesAPendingUserAndIsReturnedOnce() {
        UUID userId =
                createUser.handle(new CreateUser(null, "new.clerk", "New Clerk", "en", "BACK_OFFICE", null), admin);
        kernel.reset();

        CredentialResetResult result = resetCredential.handle(new ResetCredential(userId, "PASSWORD", null), admin);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.delivery()).isEqualTo("RETURNED");
        assertThat(result.temporaryPassword()).startsWith("Tmp");
        assertThat(result.toString()).doesNotContain(result.temporaryPassword());
        assertThat(provider.methodsFor("subject-" + userId)).containsExactly("createUser", "setTemporaryPassword");

        assertThat(kernel.committedAudit())
                .extracting(AuditRecord::eventType)
                .containsExactly("USER_CREDENTIAL_RESET", "USER_ACTIVATED");
        assertThat((Map<String, Object>) kernel.committedAudit().get(0).after())
                .containsEntry("resetKind", "PASSWORD")
                .containsEntry("delivery", "RETURNED")
                .containsEntry("status", "ACTIVE");
        assertThat(kernel.committedAudit().toString()).doesNotContain(result.temporaryPassword());
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new UserCredentialReset(userId, userId, ENTITY, "BACK_OFFICE", "ACTIVE", "PASSWORD"),
                        new UserActivated(userId, userId, ENTITY, "BACK_OFFICE", "ACTIVE"));
    }

    @Test
    void aSecondFactorResetGoesToTheProviderAndLeavesTheStatus() {
        UUID userId = insertUser(ENTITY, "finance.head", "BACK_OFFICE", "ACTIVE", "subject-finance");

        CredentialResetResult result =
                resetCredential.handle(new ResetCredential(userId, "SECOND_FACTOR", null), admin);

        assertThat(result.delivery()).isEqualTo("NONE");
        assertThat(result.temporaryPassword()).isNull();
        assertThat(provider.methodsFor("subject-finance")).containsExactly("resetTotp");
        assertThat(kernel.committedAudit()).extracting(AuditRecord::eventType).containsExactly("USER_CREDENTIAL_RESET");
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new UserCredentialReset(userId, userId, ENTITY, "BACK_OFFICE", "ACTIVE", "SECOND_FACTOR"));
    }

    @Test
    void aPinIsHashedWithArgon2idAndNeverLeavesTheRow() {
        UUID userId = insertUser(ENTITY, "till.operator", "TILL", "PENDING");

        CredentialResetResult result = resetCredential.handle(new ResetCredential(userId, "PIN", "4827"), admin);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.delivery()).isEqualTo("NONE");
        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        "select pin_hash, pin_changed_at, pin_history::text as history from security.app_user"
                                + " where user_id = ?",
                        userId);
        String hash = (String) row.get("pin_hash");
        assertThat(hash).startsWith("$argon2id$");
        assertThat(pinHasher.matches("4827", hash)).isTrue();
        assertThat(row.get("pin_changed_at")).isNotNull();
        assertThat((String) row.get("history")).contains(hash.substring(hash.lastIndexOf('$') + 1));
        assertThat(provider.calls).isEmpty();

        String recorded = kernel.committedAudit().toString() + kernel.committedEvents();
        assertThat(recorded).doesNotContain("4827").doesNotContain(hash).doesNotContain("argon2");
        assertThat((Map<String, Object>) kernel.committedAudit().get(0).after()).containsEntry("pinSet", true);
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new UserCredentialReset(userId, userId, ENTITY, "TILL", "ACTIVE", "PIN"),
                        new UserActivated(userId, userId, ENTITY, "TILL", "ACTIVE"));
        assertThat(queries.getUser(userId, admin))
                .get()
                .extracting(UserView::pinSet)
                .isEqualTo(true);
    }

    @Test
    void aNewPinUnlocksALockedOperator() {
        UUID userId = insertUser(ENTITY, "locked.out", "BOTH", "LOCKED");

        CredentialResetResult result = resetCredential.handle(new ResetCredential(userId, "PIN", "9173"), admin);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(kernel.committedEvents()).hasSize(2).last().isInstanceOf(UserActivated.class);
    }

    @Test
    void thePinPolicyRefusesLettersTheWrongLengthAndTheLastThree() {
        UUID userId = insertUser(ENTITY, "pin.policy", "TILL", "ACTIVE");

        refused("m1.user.pin_required", () -> resetCredential.handle(new ResetCredential(userId, "PIN", null), admin));
        refused(
                "m1.user.pin_digits_only",
                () -> resetCredential.handle(new ResetCredential(userId, "PIN", "12a4"), admin));
        refused(
                "m1.user.pin_digits_only",
                () -> resetCredential.handle(new ResetCredential(userId, "PIN", "١٢٣٤"), admin));
        refused("m1.user.pin_length", () -> resetCredential.handle(new ResetCredential(userId, "PIN", "123"), admin));
        refused(
                "m1.user.pin_length",
                () -> resetCredential.handle(new ResetCredential(userId, "PIN", "1234567"), admin));

        resetCredential.handle(new ResetCredential(userId, "PIN", "1234"), admin);
        refused("m1.user.pin_reused", () -> resetCredential.handle(new ResetCredential(userId, "PIN", "1234"), admin));
        resetCredential.handle(new ResetCredential(userId, "PIN", "5678"), admin);
        resetCredential.handle(new ResetCredential(userId, "PIN", "246810"), admin);
        // 1234 is the third last: still refused.
        refused("m1.user.pin_reused", () -> resetCredential.handle(new ResetCredential(userId, "PIN", "1234"), admin));
        resetCredential.handle(new ResetCredential(userId, "PIN", "13579"), admin);
        // Now 1234 is the fourth last and may come back.
        resetCredential.handle(new ResetCredential(userId, "PIN", "1234"), admin);

        String history = superuserJdbc()
                .queryForObject(
                        "select pin_history::text from security.app_user where user_id = ?", String.class, userId);
        assertThat(history.split("\\$argon2id\\$")).hasSize(4); // three hashes
    }

    @Test
    void theEntityMayNarrowThePinLength() {
        ScopeContext strictAdmin = ScopeContext.dev(ADMIN, STRICT_ENTITY, null);
        UUID userId = insertUser(STRICT_ENTITY, "strict.till", "TILL", "ACTIVE");
        superuserJdbc()
                .update(
                        "insert into kernel.config_value (key, scope_entity_id, value, reason)"
                                + " values ('security.pin.length_min', ?, '5'::jsonb, 'test')",
                        STRICT_ENTITY);

        refused(
                "m1.user.pin_length",
                () -> resetCredential.handle(new ResetCredential(userId, "PIN", "4827"), strictAdmin));
        resetCredential.handle(new ResetCredential(userId, "PIN", "48271"), strictAdmin);
    }

    @Test
    void resetCredentialGuards() {
        UUID till = insertUser(ENTITY, "till.no.login", "TILL", "ACTIVE", "subject-till");
        UUID office = insertUser(ENTITY, "office.only", "BACK_OFFICE", "ACTIVE", "subject-office");
        UUID noLogin = insertUser(ENTITY, "office.no.login", "BACK_OFFICE", "PENDING");
        UUID gone = insertUser(ENTITY, "gone.too", "BOTH", "DEACTIVATED");
        UUID elsewhere = insertUser(OTHER_ENTITY, "other.office", "BACK_OFFICE", "ACTIVE", "subject-other");

        refused(
                "m1.user.entity_scope_required",
                () -> resetCredential.handle(
                        new ResetCredential(till, "PIN", "4827"), ScopeContext.dev(ADMIN, ENTITY, SHOP_A)));
        refused(
                "m1.user.not_found",
                () -> resetCredential.handle(new ResetCredential(elsewhere, "PASSWORD", null), admin));
        refused("m1.user.deactivated", () -> resetCredential.handle(new ResetCredential(gone, "PIN", "4827"), admin));
        refused(
                "m1.user.credential_invalid",
                () -> resetCredential.handle(new ResetCredential(office, "SMS", null), admin));
        refused(
                "m1.user.password_not_applicable",
                () -> resetCredential.handle(new ResetCredential(till, "PASSWORD", null), admin));
        refused(
                "m1.user.no_login",
                () -> resetCredential.handle(new ResetCredential(noLogin, "PASSWORD", null), admin));
        refused(
                "m1.user.pin_not_applicable",
                () -> resetCredential.handle(new ResetCredential(office, "PIN", "4827"), admin));

        assertThat(provider.calls).isEmpty();
    }

    // ---- DeactivateUser ----

    @Test
    void deactivationDisablesTheLoginEndsTheSessionsAndDropsThePin() {
        UUID userId = insertUser(ENTITY, "leaving.soon", "BOTH", "ACTIVE", "subject-leaving");
        resetCredential.handle(new ResetCredential(userId, "PIN", "7391"), admin);
        kernel.reset();

        deactivateUser.handle(new DeactivateUser(userId, "LEFT_EMPLOYMENT", "Resigned in September"), admin);

        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        "select status, pin_hash, pin_history::text as history from security.app_user where user_id = ?",
                        userId);
        assertThat(row).containsEntry("status", "DEACTIVATED").containsEntry("history", "[]");
        assertThat(row.get("pin_hash")).isNull();
        assertThat(provider.methodsFor("subject-leaving")).containsExactly("disableUser", "revokeSessions");

        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo("USER_DEACTIVATED");
            assertThat(record.reason()).isEqualTo("LEFT_EMPLOYMENT: Resigned in September");
            assertThat((Map<String, Object>) record.before()).containsEntry("status", "ACTIVE");
            assertThat((Map<String, Object>) record.after()).containsEntry("status", "DEACTIVATED");
        });
        assertThat(kernel.committedEvents())
                .containsExactly(new UserDeactivated(userId, userId, ENTITY, "BOTH", "DEACTIVATED"));
    }

    @Test
    void theLastUserManagerStaysUntilAnotherHoldsTheDuty() {
        UUID onlyAdmin = insertUser(ENTITY, "only.admin", "BACK_OFFICE", "ACTIVE");
        grantUserManage(onlyAdmin);

        refused(
                "m1.user.last_user_manager",
                () -> deactivateUser.handle(new DeactivateUser(onlyAdmin, "LEFT_EMPLOYMENT", null), admin));

        UUID secondAdmin = insertUser(ENTITY, "second.admin", "BACK_OFFICE", "ACTIVE");
        grantUserManage(secondAdmin);

        deactivateUser.handle(new DeactivateUser(onlyAdmin, "LEFT_EMPLOYMENT", null), admin);
        assertThat(queries.getUser(onlyAdmin, admin))
                .get()
                .extracting(UserView::status)
                .isEqualTo("DEACTIVATED");

        // The second is now the last one again.
        refused(
                "m1.user.last_user_manager",
                () -> deactivateUser.handle(new DeactivateUser(secondAdmin, "LEFT_EMPLOYMENT", null), admin));
    }

    @Test
    void deactivateUserGuards() {
        UUID officer = insertUser(ENTITY, "the.officer", "BACK_OFFICE", "ACTIVE");
        superuserJdbc()
                .update(
                        "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, responsible_officer_user_id)"
                                + " values (?, 'M707', 'MPCS', 'MPCS 707', ?)",
                        ENTITY,
                        officer);
        UUID gone = insertUser(ENTITY, "gone.again", "TILL", "DEACTIVATED");
        UUID elsewhere = insertUser(OTHER_ENTITY, "other.till", "TILL", "ACTIVE");
        UUID till = insertUser(ENTITY, "plain.till", "TILL", "ACTIVE");

        refused(
                "m1.user.entity_scope_required",
                () -> deactivateUser.handle(
                        new DeactivateUser(till, "LEFT", null), ScopeContext.dev(ADMIN, ENTITY, SHOP_A)));
        refused("m1.user.not_found", () -> deactivateUser.handle(new DeactivateUser(elsewhere, "LEFT", null), admin));
        refused("m1.user.deactivated", () -> deactivateUser.handle(new DeactivateUser(gone, "LEFT", null), admin));
        refused("m1.user.reason_required", () -> deactivateUser.handle(new DeactivateUser(till, " ", null), admin));
        refused(
                "m1.user.responsible_officer",
                () -> deactivateUser.handle(new DeactivateUser(officer, "LEFT", null), admin));

        assertThat(provider.calls).isEmpty();
    }

    // ---- row-level security for a shop-scoped caller (m1security V0012) ----

    @Nested
    class AShopScopedCaller {

        UUID atShopA;
        UUID atShopB;
        UUID entityWide;
        UUID unassigned;
        UUID otherEntity;

        @BeforeEach
        void staffAtTwoShops() {
            atShopA = insertUser(ENTITY, "shop.a.cashier", "TILL", "ACTIVE");
            atShopB = insertUser(ENTITY, "shop.b.cashier", "TILL", "ACTIVE");
            entityWide = insertUser(ENTITY, "area.manager", "BACK_OFFICE", "ACTIVE");
            unassigned = insertUser(ENTITY, "not.yet.placed", "TILL", "PENDING");
            otherEntity = insertUser(OTHER_ENTITY, "other.society", "TILL", "ACTIVE");
            UUID role = insertRole(ENTITY, "Cashier", List.of());
            assign(atShopA, role, SHOP_A);
            assign(atShopB, role, SHOP_B);
            assign(entityWide, role, null);
        }

        @Test
        void seesItsShopsStaffAndTheEntityWideOnesButNoSiblingShop() {
            ScopeContext shopA = ScopeContext.dev(ADMIN, ENTITY, SHOP_A);

            // The shop sees its own staff, the entity-wide ones and a colleague not yet placed
            // anywhere (whom it may give a first assignment, M1-08); never a sibling shop's operator.
            assertThat(visible(shopA)).containsExactlyInAnyOrder(atShopA, entityWide, unassigned);
            assertThat(queries.getUser(atShopB, shopA)).isEmpty();
            assertThat(visible(admin)).containsExactlyInAnyOrder(atShopA, atShopB, entityWide, unassigned);
            assertThat(visible(ScopeContext.dev(ADMIN, OTHER_ENTITY, null))).containsExactly(otherEntity);
        }

        @Test
        void cannotChangeASiblingShopsOperator() {
            int changedSibling = inScope(
                    ENTITY,
                    SHOP_A,
                    () -> jdbc.update(
                            "update security.app_user set display_name = 'changed' where user_id = ?", atShopB));
            int changedOwn = inScope(
                    ENTITY,
                    SHOP_A,
                    () -> jdbc.update(
                            "update security.app_user set display_name = 'changed' where user_id = ?", atShopA));

            assertThat(changedSibling).isZero();
            assertThat(changedOwn).isEqualTo(1);
        }

        @Test
        void theDefinerFunctionsReadUsersUnderForcedRowLevelSecurity() {
            // m1security V0012: before it, a SECURITY DEFINER function with "row_security = off"
            // failed on the first user it read; M1-03's officer check is one of them.
            Boolean belongs = inScope(
                    ENTITY,
                    null,
                    () -> jdbc.queryForObject(
                            "select security.user_belongs_to_entity(?, ?)", Boolean.class, atShopB, ENTITY));
            Boolean taken = inScope(
                    OTHER_ENTITY,
                    null,
                    () -> jdbc.queryForObject("select security.username_taken(?)", Boolean.class, "SHOP.A.CASHIER"));
            Boolean takenForNobody = inScope(
                    ENTITY,
                    null,
                    () -> jdbc.queryForObject("select security.username_taken(?)", Boolean.class, "nobody.has.this"));

            assertThat(belongs).isTrue();
            assertThat(taken).isTrue();
            assertThat(takenForNobody).isFalse();
        }

        private List<UUID> visible(ScopeContext scope) {
            return queries.listUsers(new UserFilter(null, null, null, 100), scope).items().stream()
                    .map(UserView::userId)
                    .toList();
        }
    }

    // ---- over HTTP: the password is answered once and stored nowhere ----

    @Test
    void theTemporaryPasswordIsNotKeptForAReplay() {
        UUID userId = insertUser(ENTITY, "http.clerk", "BACK_OFFICE", "PENDING", "subject-http");
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of("credential", "PASSWORD"), headers);
        String url = "/v1/security/users/" + userId + "/reset-credential";

        ResponseEntity<JsonNode> first = http.exchange(url, HttpMethod.POST, request, JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getCacheControl()).contains("no-store");
        String password = first.getBody().path("temporaryPassword").asText();
        assertThat(password).startsWith("Tmp");
        assertThat(first.getBody().path("delivery").asText()).isEqualTo("RETURNED");
        assertThat(first.getBody().path("status").asText()).isEqualTo("ACTIVE");

        List<String> stored = superuserJdbc()
                .queryForList(
                        "select coalesce(response_body, '') from kernel.idempotency_key where user_id = ?",
                        String.class,
                        ADMIN);
        assertThat(stored).isNotEmpty().noneMatch(body -> body.contains(password));

        ResponseEntity<JsonNode> replay = http.exchange(url, HttpMethod.POST, request, JsonNode.class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("temporaryPassword").isMissingNode()
                        || replay.getBody().path("temporaryPassword").isNull())
                .isTrue();
        assertThat(provider.methodsFor("subject-http")).containsExactly("setTemporaryPassword");
    }

    @Test
    void aPinThatBreaksTheShapeIsRefusedBeforeTheHandler() {
        UUID userId = insertUser(ENTITY, "http.till", "TILL", "ACTIVE");
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/security/users/" + userId + "/reset-credential",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("credential", "PIN", "pin", "12ab"), headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("request.invalid");
        assertThat(response.getBody().toString()).doesNotContain("12ab");
    }

    // ---- helpers ----

    private void refused(String messageId, Executable command) {
        kernel.reset();
        assertThatThrownBy(command::execute)
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo(messageId));
        assertThat(kernel.committedAudit()).as("audit after " + messageId).isEmpty();
        assertThat(kernel.committedEvents()).as("events after " + messageId).isEmpty();
    }

    private static UUID insertUser(UUID entity, String username, String kind, String status) {
        return insertUser(entity, username, kind, status, null);
    }

    private static UUID insertUser(UUID entity, String username, String kind, String status, String subject) {
        UUID userId = UUID.randomUUID();
        superuserJdbc()
                .update(
                        "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status, provider_subject)"
                                + " values (?, ?, ?, ?, ?, ?, ?)",
                        userId,
                        entity,
                        username,
                        "Test " + username,
                        kind,
                        status,
                        subject);
        return userId;
    }

    private static UUID insertRole(UUID entity, String name, List<String> permissions) {
        UUID roleId = UUID.randomUUID();
        superuserJdbc()
                .update(
                        "insert into security.role (role_id, owner_entity_id, name_en) values (?, ?, ?)",
                        roleId,
                        entity,
                        name + " " + roleId);
        for (String permission : permissions) {
            superuserJdbc()
                    .update(
                            "insert into security.role_permission (role_id, permission_code) values (?, ?)",
                            roleId,
                            permission);
        }
        return roleId;
    }

    private static void assign(UUID userId, UUID roleId, UUID locationId) {
        superuserJdbc()
                .update(
                        "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                        userId,
                        roleId,
                        ENTITY,
                        locationId);
    }

    private static void grantUserManage(UUID userId) {
        assign(userId, insertRole(ENTITY, "Administrator", List.of("gov.user.manage")), null);
    }

    private static long count(String sql, Object... args) {
        Long n = superuserJdbc().queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private <T> T inScope(UUID entityId, UUID locationId, java.util.function.Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        return transaction.execute(status -> {
            jdbc.queryForObject("select set_config('app.scope_entity_id', ?, true)", String.class, entityId.toString());
            jdbc.queryForObject(
                    "select set_config('app.scope_location_id', ?, true)",
                    String.class,
                    locationId == null ? "" : locationId.toString());
            jdbc.queryForObject("select set_config('app.scope_class', 'OWN', true)", String.class);
            try {
                return work.get();
            } finally {
                status.setRollbackOnly();
            }
        });
    }
}
