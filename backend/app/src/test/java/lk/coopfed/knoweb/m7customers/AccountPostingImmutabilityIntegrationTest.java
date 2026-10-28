package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.OFFICE_USER;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves the doc 18 P4 rule that an account posting is an insert-only ledger fact. */
class AccountPostingImmutabilityIntegrationTest extends PostgresIntegrationTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("0190f700-0000-7000-8000-000000000031");
    private static final UUID ACCOUNT_ID = UUID.fromString("0190f700-0000-7000-8000-000000000032");
    private static final UUID POSTING_ID = UUID.fromString("0190f700-0000-7000-8000-000000000033");
    private static final UUID DOCUMENT_ID = UUID.fromString("0190f700-0000-7000-8000-000000000034");
    private static final BigDecimal ORIGINAL_AMOUNT = new BigDecimal("125.50");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void arrange() {
        JdbcTemplate admin = superuserJdbc();
        CustomersFixture.arrange(admin);
        admin.update(
                """
                insert into customers.customer (
                    customer_id, display_name, registered_by_entity_id, owner_entity_id
                )
                values (?, 'Posting immutability test member', ?, ?)
                """,
                CUSTOMER_ID,
                SOCIETY,
                SOCIETY);
        admin.update(
                """
                insert into customers.customer_account (
                    account_id, customer_id, account_no, credit_limit, balance,
                    opened_at, opened_by, owner_entity_id
                )
                values (?, ?, 'QA16-ACCOUNT', 1000.00, ?, now(), ?, ?)
                """,
                ACCOUNT_ID,
                CUSTOMER_ID,
                ORIGINAL_AMOUNT,
                OFFICE_USER,
                SOCIETY);
        admin.update(
                """
                insert into customers.account_posting (
                    posting_id, account_id, kind, amount, business_date,
                    document_id, owner_entity_id
                )
                values (?, ?, 'CHARGE', ?, ?, ?, ?)
                """,
                POSTING_ID,
                ACCOUNT_ID,
                ORIGINAL_AMOUNT,
                LocalDate.of(2026, 10, 28),
                DOCUMENT_ID,
                SOCIETY);
    }

    @AfterEach
    void clean() {
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void appRwCanSeeButCannotUpdateAnAccountPosting() {
        Posting before = inSocietyScope(() -> {
            assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("coop_app");
            assertThat(jdbc.queryForObject("select pg_has_role(current_user, 'app_rw', 'member')", Boolean.class))
                    .isTrue();
            return posting();
        });
        assertThat(before.amount()).isEqualByComparingTo(ORIGINAL_AMOUNT);
        assertThat(before.kind()).isEqualTo("CHARGE");

        assertThatThrownBy(() -> inSocietyScope(() -> jdbc.update(
                        "update customers.account_posting set amount = 999.00 where posting_id = ?", POSTING_ID)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("permission denied for table account_posting");

        assertThat(inSocietyScope(this::posting)).isEqualTo(before);
    }

    private Posting posting() {
        return jdbc.queryForObject(
                """
                select posting_id, account_id, kind, amount, business_date,
                       document_id, owner_entity_id
                  from customers.account_posting
                 where posting_id = ?
                """,
                (rs, rowNum) -> new Posting(
                        rs.getObject("posting_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("kind"),
                        rs.getBigDecimal("amount"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class)),
                POSTING_ID);
    }

    private <T> T inSocietyScope(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("select set_config('app.scope_entity_id', ?, true)", String.class, SOCIETY.toString());
            jdbc.queryForObject("select set_config('app.scope_location_id', '', true)", String.class);
            jdbc.queryForObject("select set_config('app.scope_class', 'OWN', true)", String.class);
            jdbc.queryForObject("select set_config('app.granted_entities', '{}', true)", String.class);
            try {
                return work.get();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    private record Posting(
            UUID postingId,
            UUID accountId,
            String kind,
            BigDecimal amount,
            LocalDate businessDate,
            UUID documentId,
            UUID ownerEntityId) {}
}
