package lk.coopfed.knoweb.m7customers.internal.snapshot;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.OTHER;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.office;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.other;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.till;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor.Shop;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.m7customers.CustomersFixture;
import lk.coopfed.knoweb.m7customers.api.AmendAccountLimits;
import lk.coopfed.knoweb.m7customers.api.ChangeAccountStatus;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.PostAccountTender;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M7's snapshot contributor (27A section 7.3) in the scope of a till at the society's shop: the
 * customers with an OPEN or SUSPENDED account of the shop's society, with what the till's account
 * tender and repayment rules need (limit, balance, offline cap, hard block, status) as decimal
 * text, and never the NIC, the attributes or the consents; a CLOSED account, a customer without an
 * account and another society's account are not in it.
 */
class CustomerSnapshotContributorIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    CustomerSnapshotContributor contributor;

    @Autowired
    SystemScope transactions;

    @Autowired
    Handles<RegisterCustomer, UUID> register;

    @Autowired
    Handles<OpenAccount, UUID> open;

    @Autowired
    Handles<PostAccountTender, UUID> tender;

    @Autowired
    Handles<AmendAccountLimits, UUID> amendLimits;

    @Autowired
    Handles<ChangeAccountStatus, UUID> changeStatus;

    private final Shop shop = new Shop(SOCIETY, SHOP);

    @BeforeEach
    void arrange() {
        CustomersFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void theTillGetsTheSocietysCreditCustomersAndNothingPrivate() {
        UUID open1 = member("K. Perera", "කේ. පෙරේරා", "0700000601", List.of("regular", "near"), SOCIETY);
        UUID openAccount = open.handle(
                new OpenAccount(open1, new BigDecimal("15000"), 30, new BigDecimal("5000"), "190000000601"), office());
        amendLimits.handle(new AmendAccountLimits(openAccount, null, true, null, "Hard block"), office());
        tender.handle(
                new PostAccountTender(
                        PostAccountTender.CHARGE,
                        openAccount,
                        new BigDecimal("6200.00"),
                        UUID.randomUUID(),
                        "RCT-1",
                        1,
                        SHOP,
                        LocalDate.of(2026, 9, 1),
                        null,
                        false),
                till());

        UUID suspended = member("Sita Kumari", null, "0700000602", List.of(), SOCIETY);
        UUID suspendedAccount = open.handle(new OpenAccount(suspended, BigDecimal.ZERO, null, null, null), office());
        changeStatus.handle(
                new ChangeAccountStatus(suspendedAccount, ChangeAccountStatus.SUSPEND, "Overdue"), office());

        UUID closed = member("R. Wijesinghe", null, "0700000603", List.of(), SOCIETY);
        UUID closedAccount = open.handle(new OpenAccount(closed, BigDecimal.ZERO, null, null, null), office());
        changeStatus.handle(new ChangeAccountStatus(closedAccount, ChangeAccountStatus.CLOSE, "Left"), office());

        UUID noAccount = member("Anoma Herath", null, "0700000604", List.of(), SOCIETY);
        UUID elsewhere = member("S. Sivakumar", null, "0700000605", List.of(), OTHER);
        open.handle(new OpenAccount(elsewhere, BigDecimal.ZERO, null, null, null), other());

        Map<UUID, Map<String, Object>> rows =
                transactions.inScope(SystemScope.own(SOCIETY, SHOP), () -> contributor.allRows("customer", shop));

        assertThat(rows).containsOnlyKeys(open1, suspended);
        Map<String, Object> row = rows.get(open1);
        assertThat(row)
                .containsEntry("display_name", "K. Perera")
                .containsEntry("display_name_si", "කේ. පෙරේරා")
                .containsEntry("phone", "+94700000601")
                .containsEntry("language", "si")
                .containsEntry("account_id", openAccount.toString())
                .containsEntry("credit_limit", "15000.00")
                .containsEntry("balance", "6200.00")
                .containsEntry("offline_cap", "5000.00")
                .containsEntry("hard_block", true)
                .containsEntry("status", "OPEN")
                .containsEntry("tags", List.of("near", "regular"))
                .doesNotContainKeys("nic_hash", "nic_last4", "attributes", "consents");
        assertThat(row.toString()).doesNotContain("0601\"", "Kuliyapitiya");
        assertThat(rows.get(suspended)).containsEntry("status", "SUSPENDED");

        // The rows asked for by id: a row that left (closed, no account) is simply not answered.
        Map<UUID, Map<String, Object>> some = transactions.inScope(
                SystemScope.own(SOCIETY, SHOP),
                () -> contributor.rows("customer", shop, List.of(open1, closed, noAccount, elsewhere)));
        assertThat(some).containsOnlyKeys(open1);
        assertThat(contributor.tables()).containsExactly("customer");
    }

    private UUID member(String name, String nameSi, String phone, List<String> tags, UUID society) {
        return register.handle(
                new RegisterCustomer(
                        name,
                        nameSi,
                        null,
                        "si",
                        phone,
                        List.of("CREDIT_ACCOUNT"),
                        "PAPER",
                        Map.of("village", "Kuliyapitiya"),
                        tags,
                        false),
                society.equals(SOCIETY) ? office() : other());
    }
}
