package lk.coopfed.knoweb.m7customers.internal.consumers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.m7customers.CustomersFixture;
import lk.coopfed.knoweb.m7customers.api.AccountCharged;
import lk.coopfed.knoweb.m7customers.api.AccountCredited;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The receipt tender consumer (27A section 6.2): of a till's receipt bundle (doc 32 section 3.1),
 * the ACCOUNT tender becomes a CHARGE on the customer's account and the CASH tender is left to M6;
 * a refund's ACCOUNT tender becomes a CREDIT.
 */
class ReceiptTenderConsumerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    ReceiptTenderConsumer consumer;

    @Autowired
    Handles<RegisterCustomer, UUID> register;

    @Autowired
    Handles<OpenAccount, UUID> open;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void arrange() {
        CustomersFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void theAccountTenderOfAReceiptIsChargedAndOfARefundCredited() {
        UUID customerId = register.handle(
                new RegisterCustomer(
                        "Mohamed Fazil",
                        null,
                        null,
                        "en",
                        "0700000301",
                        List.of("CREDIT_ACCOUNT"),
                        "PAPER",
                        null,
                        null,
                        false),
                CustomersFixture.office());
        UUID accountId = open.handle(
                new OpenAccount(customerId, new BigDecimal("10000"), null, null, "190000000301"),
                CustomersFixture.office());
        kernel.reset();

        consumer.onIssued(bundle(accountId, "2026-09-10", "2500.00", "500.00"), CustomersFixture.till());

        assertThat(kernel.committedEvents()).singleElement().isInstanceOfSatisfying(AccountCharged.class, e -> {
            assertThat(e.accountId()).isEqualTo(accountId);
            assertThat(e.amount()).isEqualByComparingTo("2500.00");
            assertThat(e.balance()).isEqualByComparingTo("2500.00");
        });
        kernel.reset();

        consumer.onRefundedOrVoided(bundle(accountId, "2026-09-11", "400.00", "0.00"), CustomersFixture.till());
        assertThat(kernel.committedEvents()).singleElement().isInstanceOfSatisfying(AccountCredited.class, e -> {
            assertThat(e.amount()).isEqualByComparingTo("-400.00");
            assertThat(e.balance()).isEqualByComparingTo("2100.00");
        });
        BigDecimal balance = superuserJdbc()
                .queryForObject(
                        "select balance from customers.customer_account where account_id = ?",
                        BigDecimal.class,
                        accountId);
        assertThat(balance).isEqualByComparingTo("2100.00");
    }

    /** A receipt bundle with an ACCOUNT tender and a CASH tender, as the till writes it. */
    private JsonNode bundle(UUID accountId, String businessDate, String onAccount, String cash) {
        return json.valueToTree(Map.of(
                "document",
                Map.of(
                        "document_id",
                        UUID.randomUUID().toString(),
                        "doc_number_display",
                        "M7S-S1-T1-0000042",
                        "location_id",
                        CustomersFixture.SHOP.toString(),
                        "business_date",
                        businessDate),
                "tenders",
                List.of(
                        Map.of(
                                "seq",
                                1,
                                "kind",
                                "ACCOUNT",
                                "amount",
                                onAccount,
                                "customer_account_id",
                                accountId.toString(),
                                "offline",
                                false),
                        Map.of("seq", 2, "kind", "CASH", "amount", cash))));
    }
}
