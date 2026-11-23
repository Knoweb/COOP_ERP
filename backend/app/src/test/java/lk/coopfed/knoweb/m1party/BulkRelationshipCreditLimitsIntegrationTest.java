package lk.coopfed.knoweb.m1party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.BulkRelationshipCreditLimitReport;
import lk.coopfed.knoweb.m1party.api.BulkSetRelationshipCreditLimits;
import lk.coopfed.knoweb.m1party.api.CreditLimitChanged;
import lk.coopfed.knoweb.m1party.api.RelationshipCreditLimitsBulkImported;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves CAT-14: Limits load from a sheet with step-up for trading relationships.
 */
class BulkRelationshipCreditLimitsIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER = UUID.fromString("0190a708-0000-7000-8000-000000000010");
    private static final UUID BUYER_A = UUID.fromString("0190a708-0000-7000-8000-000000000020");
    private static final UUID BUYER_B = UUID.fromString("0190a708-0000-7000-8000-000000000030");
    private static final UUID USER = UUID.fromString("0190a708-0000-7000-8000-000000000040");
    private static final UUID ROLE = UUID.fromString("0190a708-0000-7000-8000-000000000050");
    private static final UUID PRICE_LIST = UUID.fromString("0190a708-0000-7000-8000-000000000060");

    @Autowired
    private Handles<BulkSetRelationshipCreditLimits, BulkRelationshipCreditLimitReport> bulkLimits;

    private UUID relAId;
    private UUID relBId;

    @BeforeEach
    void setUp() {
        JdbcTemplate admin = superuserJdbc();
        clean(admin);

        // Seed entities and roles
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, status) values (?, 'D-SELLER', 'DISTRIBUTOR', 'Seller Distributor', 'ACTIVE')",
                SELLER);
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, status) values (?, 'B-SOCIETY-A', 'MPCS', 'Buyer Society A', 'ACTIVE')",
                BUYER_A);
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, status) values (?, 'B-SOCIETY-B', 'MPCS', 'Buyer Society B', 'ACTIVE')",
                BUYER_B);

        // Users & permissions for Credit Controller
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status) values (?, ?, 'credit-ctrl', 'Credit Controller', 'BACK_OFFICE', 'ACTIVE')",
                USER,
                SELLER);
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status) values (?, ?, 'Credit Controller Role', false, 'OWN', 'ACTIVE')",
                ROLE,
                SELLER);
        admin.update(
                "insert into security.role_permission (role_id, permission_code) values (?, 'bil.creditlimit.change')",
                ROLE);
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, null)",
                USER,
                ROLE,
                SELLER);

        // Relationships: Rel A is ACTIVE, Rel B is DRAFT
        relAId = Ids.next();
        relBId = Ids.next();
        LocalDate today = LocalDate.now();

        admin.update(
                """
                insert into party.entity_relationship (
                    relationship_id, seller_entity_id, buyer_entity_id, status, price_list_id, credit_limit,
                    payment_terms_days, discrepancy_window_days, effective_from
                ) values (?, ?, ?, 'ACTIVE', ?, 10000.00, 30, 7, ?)
                """,
                relAId,
                SELLER,
                BUYER_A,
                PRICE_LIST,
                today.minusDays(10));

        admin.update(
                """
                insert into party.entity_relationship (
                    relationship_id, seller_entity_id, buyer_entity_id, status, price_list_id, credit_limit,
                    payment_terms_days, discrepancy_window_days, effective_from
                ) values (?, ?, ?, 'DRAFT', ?, 20000.00, 30, 7, ?)
                """,
                relBId,
                SELLER,
                BUYER_B,
                PRICE_LIST,
                today.plusDays(5));

        kernel.reset();
    }

    @AfterEach
    void tearDown() {
        clean(superuserJdbc());
    }

    private void clean(JdbcTemplate admin) {
        admin.update("delete from security.user_role where user_id = ?", USER);
        admin.update("delete from security.role_permission where role_id = ?", ROLE);
        admin.update("delete from security.role where role_id = ?", ROLE);
        admin.update("delete from security.app_user where user_id = ?", USER);
        admin.update("delete from party.entity_relationship where seller_entity_id = ?", SELLER);
        admin.update("delete from party.entity_party_directory where entity_id in (?, ?, ?)", SELLER, BUYER_A, BUYER_B);
        admin.update("delete from party.entity where entity_id in (?, ?, ?)", SELLER, BUYER_A, BUYER_B);
    }

    private ScopeContext withFreshMfa() {
        Scope active = new Scope(SELLER, null);
        return new ScopeContext(
                USER,
                null,
                SELLER,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                Instant.now(),
                Locale.ENGLISH,
                null);
    }

    private ScopeContext withoutMfa() {
        Scope active = new Scope(SELLER, null);
        return new ScopeContext(
                USER, null, SELLER, List.of(active), active, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private ScopeContext withStaleMfa() {
        Scope active = new Scope(SELLER, null);
        return new ScopeContext(
                USER,
                null,
                SELLER,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                Instant.now().minus(Duration.ofMinutes(15)),
                Locale.ENGLISH,
                null);
    }

    @Test
    void limitsLoadFromASheetWithStepUp() {
        String csv =
                """
                buyer_code,credit_limit,reason
                B-SOCIETY-A,50000.00,Approved increase
                B-SOCIETY-B,75000.00,Revised terms
                """;

        BulkRelationshipCreditLimitReport report =
                bulkLimits.handle(new BulkSetRelationshipCreditLimits(csv, null, "Annual review"), withFreshMfa());

        assertThat(report.status()).isEqualTo(BulkRelationshipCreditLimitReport.UPDATED);
        assertThat(report.updatedCount()).isEqualTo(2);
        assertThat(report.problems()).isEmpty();

        // Rel B (DRAFT) updated in place
        BigDecimal limitB = superuserJdbc()
                .queryForObject(
                        "select credit_limit from party.entity_relationship where relationship_id = ?",
                        BigDecimal.class,
                        relBId);
        assertThat(limitB).isEqualByComparingTo("75000.00");

        // Rel A (ACTIVE) created an effective-dated successor row
        BigDecimal limitA = superuserJdbc()
                .queryForObject(
                        "select credit_limit from party.entity_relationship where seller_entity_id = ? and buyer_entity_id = ? and status = 'ACTIVE' order by effective_from desc limit 1",
                        BigDecimal.class,
                        SELLER,
                        BUYER_A);
        assertThat(limitA).isEqualByComparingTo("50000.00");

        // Assert Audited
        assertThat(kernel.committedAudit())
                .anySatisfy(a -> {
                    assertThat(a.eventType()).isEqualTo("CREDIT_LIMITS_BULK_IMPORTED");
                    assertThat(a.subject().id()).isEqualTo(SELLER);
                    Map<?, ?> after = (Map<?, ?>) a.after();
                    assertThat(after.get("count")).isEqualTo(2);
                    assertThat(after.get("totalCreditLimit")).isEqualTo("125000.00");
                })
                .anySatisfy(a -> assertThat(a.eventType()).isEqualTo("CREDIT_LIMIT_CHANGED"));

        // Assert Events
        assertThat(kernel.committedEvents())
                .anySatisfy(e -> assertThat(e)
                        .isInstanceOfSatisfying(RelationshipCreditLimitsBulkImported.class, bulkEvent -> {
                            assertThat(bulkEvent.sellerEntityId()).isEqualTo(SELLER);
                            assertThat(bulkEvent.importedCount()).isEqualTo(2);
                            assertThat(bulkEvent.totalCreditLimit()).isEqualByComparingTo("125000.00");
                        }))
                .anySatisfy(e -> assertThat(e).isInstanceOf(CreditLimitChanged.class));
    }

    @Test
    void missingStepUpRefusesImport() {
        String csv =
                """
                buyer_code,credit_limit,reason
                B-SOCIETY-A,50000.00,Attempt without MFA
                """;

        assertThatThrownBy(
                        () -> bulkLimits.handle(new BulkSetRelationshipCreditLimits(csv, null, "No MFA"), withoutMfa()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("mfa.required");

        assertThatThrownBy(() ->
                        bulkLimits.handle(new BulkSetRelationshipCreditLimits(csv, null, "Stale MFA"), withStaleMfa()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("mfa.required");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // Database unchanged
        BigDecimal limitA = superuserJdbc()
                .queryForObject(
                        "select credit_limit from party.entity_relationship where relationship_id = ?",
                        BigDecimal.class,
                        relAId);
        assertThat(limitA).isEqualByComparingTo("10000.00");
    }

    @Test
    void invalidRowRejectsEntireBatchWithoutChanges() {
        String csv =
                """
                buyer_code,credit_limit,reason
                B-SOCIETY-A,50000.00,Valid row
                UNKNOWN-BUYER,75000.00,Nonexistent buyer
                """;

        BulkRelationshipCreditLimitReport report =
                bulkLimits.handle(new BulkSetRelationshipCreditLimits(csv, null, "Review"), withFreshMfa());

        assertThat(report.status()).isEqualTo(BulkRelationshipCreditLimitReport.REJECTED);
        assertThat(report.updatedCount()).isZero();
        assertThat(report.problems()).hasSize(1);
        assertThat(report.problems().get(0).field()).isEqualTo("buyer");
        assertThat(report.problems().get(0).code()).isEqualTo("m1.relationship.buyer_not_found");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // Row 1 must NOT have been updated (all-or-none)
        BigDecimal limitA = superuserJdbc()
                .queryForObject(
                        "select credit_limit from party.entity_relationship where relationship_id = ?",
                        BigDecimal.class,
                        relAId);
        assertThat(limitA).isEqualByComparingTo("10000.00");
    }
}
