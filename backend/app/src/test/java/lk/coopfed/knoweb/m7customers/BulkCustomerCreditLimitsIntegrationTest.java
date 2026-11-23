package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.OFFICE_USER;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.arrange;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.cleanAllCustomers;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.office;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.officeWithMfa;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m7customers.api.AccountLimitsAmended;
import lk.coopfed.knoweb.m7customers.api.BulkCustomerCreditLimitReport;
import lk.coopfed.knoweb.m7customers.api.BulkSetCustomerCreditLimits;
import lk.coopfed.knoweb.m7customers.api.CustomerCreditLimitsBulkImported;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves CAT-14: Limits load from a sheet with step-up for customer accounts.
 */
class BulkCustomerCreditLimitsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private Handles<BulkSetCustomerCreditLimits, BulkCustomerCreditLimitReport> bulkLimits;

    private UUID accountId1;
    private UUID accountId2;

    @BeforeEach
    void setUp() {
        JdbcTemplate admin = superuserJdbc();
        arrange(admin);
        cleanAllCustomers(admin);

        // Seed 2 customers and accounts
        UUID customerId1 = Ids.next();
        UUID customerId2 = Ids.next();
        accountId1 = Ids.next();
        accountId2 = Ids.next();

        admin.update(
                """
                insert into customers.customer (
                    customer_id, display_name, language, status, registered_by_entity_id, owner_entity_id
                ) values (?, 'Customer A', 'en', 'ACTIVE', ?, ?)
                """,
                customerId1,
                SOCIETY,
                SOCIETY);
        admin.update(
                """
                insert into customers.customer (
                    customer_id, display_name, language, status, registered_by_entity_id, owner_entity_id
                ) values (?, 'Customer B', 'si', 'ACTIVE', ?, ?)
                """,
                customerId2,
                SOCIETY,
                SOCIETY);

        admin.update(
                """
                insert into customers.customer_account (
                    account_id, customer_id, account_no, credit_limit, balance,
                    hard_block, offline_cap, status, opened_at, opened_by, owner_entity_id
                ) values (?, ?, 'ACC-001', 5000.00, 0.00, false, 1000.00, 'OPEN', now(), ?, ?)
                """,
                accountId1,
                customerId1,
                OFFICE_USER,
                SOCIETY);
        admin.update(
                """
                insert into customers.customer_account (
                    account_id, customer_id, account_no, credit_limit, balance,
                    hard_block, offline_cap, status, opened_at, opened_by, owner_entity_id
                ) values (?, ?, 'ACC-002', 8000.00, 0.00, false, 2000.00, 'OPEN', now(), ?, ?)
                """,
                accountId2,
                customerId2,
                OFFICE_USER,
                SOCIETY);

        admin.update(
                """
                insert into customers.customer_phone (
                    phone_id, customer_id, phone, is_primary, owner_entity_id
                ) values (?, ?, '+94771234567', true, ?)
                """,
                Ids.next(),
                customerId2,
                SOCIETY);

        kernel.reset();
    }

    @AfterEach
    void tearDown() {
        cleanAllCustomers(superuserJdbc());
    }

    @Test
    void limitsLoadFromASheetWithStepUp() {
        String csv =
                """
                account_no,phone,credit_limit,reason
                ACC-001,,15000.00,Member limit upgrade
                ,0771234567,25000.00,Good repayment history
                """;

        BulkCustomerCreditLimitReport report =
                bulkLimits.handle(new BulkSetCustomerCreditLimits(csv, null, "Routine review"), officeWithMfa());

        assertThat(report.status()).isEqualTo(BulkCustomerCreditLimitReport.UPDATED);
        assertThat(report.updatedCount()).isEqualTo(2);
        assertThat(report.problems()).isEmpty();

        // Verify account limits in database
        BigDecimal limit1 = superuserJdbc()
                .queryForObject(
                        "select credit_limit from customers.customer_account where account_id = ?",
                        BigDecimal.class,
                        accountId1);
        assertThat(limit1).isEqualByComparingTo("15000.00");

        BigDecimal limit2 = superuserJdbc()
                .queryForObject(
                        "select credit_limit from customers.customer_account where account_id = ?",
                        BigDecimal.class,
                        accountId2);
        assertThat(limit2).isEqualByComparingTo("25000.00");

        // Verify account_history rows
        Integer historyCount = superuserJdbc()
                .queryForObject(
                        "select count(*) from customers.account_history where action = 'LIMITS_AMENDED'",
                        Integer.class);
        assertThat(historyCount).isEqualTo(2);

        // Assert Audit
        assertThat(kernel.committedAudit())
                .anySatisfy(a -> {
                    assertThat(a.eventType()).isEqualTo("CUSTOMER_LIMITS_BULK_IMPORTED");
                    assertThat(a.subject().id()).isEqualTo(SOCIETY);
                    Map<?, ?> after = (Map<?, ?>) a.after();
                    assertThat(after.get("count")).isEqualTo(2);
                    assertThat(after.get("totalCreditLimit")).isEqualTo("40000.00");
                })
                .anySatisfy(a -> assertThat(a.eventType()).isEqualTo("ACCOUNT_LIMIT_AMENDED"));

        // Assert Events
        assertThat(kernel.committedEvents())
                .anySatisfy(
                        e -> assertThat(e).isInstanceOfSatisfying(CustomerCreditLimitsBulkImported.class, bulkEvent -> {
                            assertThat(bulkEvent.societyEntityId()).isEqualTo(SOCIETY);
                            assertThat(bulkEvent.importedCount()).isEqualTo(2);
                            assertThat(bulkEvent.totalCreditLimit()).isEqualByComparingTo("40000.00");
                        }))
                .anySatisfy(e -> assertThat(e).isInstanceOf(AccountLimitsAmended.class));
    }

    @Test
    void missingStepUpRefusesImport() {
        String csv =
                """
                account_no,phone,credit_limit,reason
                ACC-001,,15000.00,Attempt without MFA
                """;

        assertThatThrownBy(() -> bulkLimits.handle(new BulkSetCustomerCreditLimits(csv, null, "No MFA"), office()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("mfa.required");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // Limits unchanged
        BigDecimal limit1 = superuserJdbc()
                .queryForObject(
                        "select credit_limit from customers.customer_account where account_id = ?",
                        BigDecimal.class,
                        accountId1);
        assertThat(limit1).isEqualByComparingTo("5000.00");
    }

    @Test
    void invalidRowRejectsEntireBatchWithoutChanges() {
        String csv =
                """
                account_no,phone,credit_limit,reason
                ACC-001,,15000.00,Valid row
                NONEXISTENT,,25000.00,Invalid account
                """;

        BulkCustomerCreditLimitReport report =
                bulkLimits.handle(new BulkSetCustomerCreditLimits(csv, null, "Review"), officeWithMfa());

        assertThat(report.status()).isEqualTo(BulkCustomerCreditLimitReport.REJECTED);
        assertThat(report.updatedCount()).isZero();
        assertThat(report.problems()).hasSize(1);
        assertThat(report.problems().get(0).field()).isEqualTo("account");
        assertThat(report.problems().get(0).code()).isEqualTo("m7.account.not_found");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // Account 1 was NOT updated
        BigDecimal limit1 = superuserJdbc()
                .queryForObject(
                        "select credit_limit from customers.customer_account where account_id = ?",
                        BigDecimal.class,
                        accountId1);
        assertThat(limit1).isEqualByComparingTo("5000.00");
    }
}
