package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.OTHER;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.office;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.other;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.till;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m7customers.api.AccountCharged;
import lk.coopfed.knoweb.m7customers.api.AccountOpened;
import lk.coopfed.knoweb.m7customers.api.AmendCustomer;
import lk.coopfed.knoweb.m7customers.api.ChangePhone;
import lk.coopfed.knoweb.m7customers.api.ConsentRecorded;
import lk.coopfed.knoweb.m7customers.api.CustomerDeactivated;
import lk.coopfed.knoweb.m7customers.api.CustomerPaymentRecorded;
import lk.coopfed.knoweb.m7customers.api.CustomerRegistered;
import lk.coopfed.knoweb.m7customers.api.DeactivateCustomer;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.PostAccountTender;
import lk.coopfed.knoweb.m7customers.api.RecordCustomerPayment;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.m7customers.query.AccountView;
import lk.coopfed.knoweb.m7customers.query.CustomerCard;
import lk.coopfed.knoweb.m7customers.query.CustomerQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder.AuditRecord;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The handlers of M7's back office (27A section 6) against PostgreSQL under row-level security:
 * registration with reuse detection (6.1), the account and its NIC, the till's account tenders
 * (6.2), repayment allocation (6.3), what each audits and publishes, and what another society
 * sees. No test depends on today's date: the business dates are read back from the records.
 */
class CustomerHandlersIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    Handles<RegisterCustomer, UUID> register;

    @Autowired
    Handles<AmendCustomer, UUID> amend;

    @Autowired
    Handles<ChangePhone, UUID> changePhone;

    @Autowired
    Handles<DeactivateCustomer, UUID> deactivate;

    @Autowired
    Handles<OpenAccount, UUID> open;

    @Autowired
    Handles<PostAccountTender, UUID> tender;

    @Autowired
    Handles<RecordCustomerPayment, UUID> pay;

    @Autowired
    CustomerQueries customers;

    @Autowired
    AccountQueries accounts;

    @BeforeEach
    void arrange() {
        CustomersFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        CustomersFixture.clean(superuserJdbc());
    }

    // ---- registration and reuse detection (27A 6.1) ------------------------------------------------

    @Test
    void registeringAuditsAndPublishesWithoutAPhoneOrAName() {
        UUID id = registerMember("Sita Kumari", "070 000 0101", false);

        CustomerCard card = customers.card(id, office()).orElseThrow();
        assertThat(card.phone()).isEqualTo("+94700000101");
        assertThat(card.status()).isEqualTo("ACTIVE");
        assertThat(card.consents()).extracting(CustomerCard.Consent::purpose).containsExactly("CREDIT_ACCOUNT");
        assertThat(card.tags()).containsExactly("regular");
        assertThat(card.registeredHere()).isTrue();

        AuditRecord audit = single("CUSTOMER_REGISTERED");
        assertThat(audit.subject().id()).isEqualTo(id);
        assertThat(String.valueOf(audit.after())).doesNotContain("0700000101", "700000101", "Sita");
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new CustomerRegistered(id, SOCIETY), new ConsentRecorded(id, SOCIETY, "CREDIT_ACCOUNT"));
        for (DomainEvent event : kernel.committedEvents()) {
            assertThat(String.valueOf(event)).doesNotContain("700000101");
        }
    }

    @Test
    void aPhoneHeldNowIsRefusedAndARecentlyReleasedOneNeedsConfirmation() {
        UUID first = registerMember("R. Wijesinghe", "0700000102", false);

        // Held now by a customer of this society: refused, naming that customer.
        assertThatThrownBy(() -> registerMember("Anoma Herath", "+94700000102", false))
                .isInstanceOfSatisfying(ProblemException.class, p -> {
                    assertThat(p.getMessage()).contains("m7.customer.phone_held");
                });
        // Held now by a customer of another society: refused, naming nobody.
        assertThatThrownBy(() -> register.handle(command("Other member", "0700000102", false), other()))
                .isInstanceOfSatisfying(
                        ProblemException.class, p -> assertThat(p.getMessage()).contains("phone_held_elsewhere"));

        // The first customer gives the number up: a new holder within the window needs confirmation.
        changePhone.handle(new ChangePhone(first, "0700000103", "New SIM", false), office());
        kernel.reset();
        assertThatThrownBy(() -> registerMember("Anoma Herath", "0700000102", false))
                .isInstanceOfSatisfying(
                        ProblemException.class, p -> assertThat(p.getMessage()).contains("phone_reuse_confirm"));
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        UUID second = registerMember("Anoma Herath", "0700000102", true);
        AuditRecord audit = single("CUSTOMER_REGISTERED");
        assertThat(audit.subject().id()).isEqualTo(second);
        assertThat(String.valueOf(audit.after())).contains(first.toString());

        CustomerCard card = customers.card(first, office()).orElseThrow();
        assertThat(card.phone()).isEqualTo("+94700000103");
        assertThat(card.phones()).hasSize(2);
    }

    @Test
    void registrationGuards() {
        assertThatThrownBy(() -> register.handle(
                        new RegisterCustomer(
                                "No consent", null, null, "si", "0700000104", List.of(), "WEB", null, null, false),
                        office()))
                .hasMessageContaining("m7.customer.consent_required");
        assertThatThrownBy(() -> registerMember("Bad phone", "12345", false))
                .hasMessageContaining("m7.customer.phone_invalid");
        assertThatThrownBy(() -> register.handle(
                        new RegisterCustomer(
                                "Bad tag",
                                null,
                                null,
                                "si",
                                "0700000105",
                                List.of("CREDIT_ACCOUNT"),
                                "WEB",
                                null,
                                List.of("Not A Tag"),
                                false),
                        office()))
                .hasMessageContaining("m7.customer.tag_invalid");
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void amendingReplacesTheNamesAttributesAndTags() {
        UUID id = registerMember("P. Jayawardena", "0700000106", false);
        kernel.reset();

        amend.handle(
                new AmendCustomer(
                        id,
                        "P. Jayawardena",
                        "පී. ජයවර්ධන",
                        null,
                        "ta",
                        Map.of("village", "Hettipola"),
                        List.of("pensioner")),
                office());

        CustomerCard card = customers.card(id, office()).orElseThrow();
        assertThat(card.displayNameSi()).isEqualTo("පී. ජයවර්ධන");
        assertThat(card.language()).isEqualTo("ta");
        assertThat(card.attributes()).containsEntry("village", "Hettipola");
        assertThat(card.tags()).containsExactly("pensioner");
        assertThat(single("CUSTOMER_UPDATED").before().toString()).contains("regular");
        // Another society cannot amend (nor see) the customer.
        assertThatThrownBy(
                        () -> amend.handle(new AmendCustomer(id, "X", null, null, "si", Map.of(), List.of()), other()))
                .hasMessageContaining("m7.customer.not_found");
    }

    // ---- accounts --------------------------------------------------------------------------------

    @Test
    void anAccountWithALimitNeedsTheNicAndOnePersonHasOneNic() {
        UUID a = registerMember("Nimal Rathnayake", "0700000107", false);
        UUID b = registerMember("Sunil Bandara", "0700000108", false);

        assertThatThrownBy(() -> open.handle(new OpenAccount(a, new BigDecimal("10000"), null, null, null), office()))
                .hasMessageContaining("m7.account.nic_required");
        kernel.reset();

        UUID accountId = open.handle(new OpenAccount(a, new BigDecimal("10000"), null, null, "190000000001"), office());
        AccountView account = accounts.account(accountId, office()).orElseThrow();
        assertThat(account.accountNo()).isEqualTo("A00001");
        assertThat(account.creditLimit()).isEqualByComparingTo("10000");
        assertThat(account.offlineCap()).isEqualByComparingTo("5000");
        assertThat(account.termsDays()).isEqualTo(30);
        assertThat(customers.card(a, office()).orElseThrow().nicLast4()).isEqualTo("0001");
        AuditRecord audit = single("ACCOUNT_OPENED");
        assertThat(String.valueOf(audit.after())).doesNotContain("190000000001");
        assertThat(kernel.committedEvents())
                .containsExactly(new AccountOpened(accountId, a, SOCIETY, new BigDecimal("10000.00")));

        assertThatThrownBy(() -> open.handle(new OpenAccount(a, BigDecimal.ZERO, null, null, null), office()))
                .hasMessageContaining("m7.account.exists");
        assertThatThrownBy(() ->
                        open.handle(new OpenAccount(b, new BigDecimal("5000"), null, null, "190000000001"), office()))
                .hasMessageContaining("m7.account.nic_held");
    }

    @Test
    void deactivatingIsRefusedWhileTheAccountHasABalance() {
        UUID id = registerMember("Kamala Dissanayake", "0700000109", false);
        UUID accountId = open.handle(new OpenAccount(id, new BigDecimal("5000"), null, null, "190000000002"), office());
        charge(accountId, "1200.00", LocalDate.of(2026, 9, 1));
        kernel.reset();

        assertThatThrownBy(() -> deactivate.handle(new DeactivateCustomer(id, "Moved away"), office()))
                .hasMessageContaining("m7.customer.open_balance");
        assertThat(kernel.committedAudit()).isEmpty();

        pay.handle(new RecordCustomerPayment(accountId, "CASH", new BigDecimal("1200"), null, null, null), office());
        kernel.reset();
        deactivate.handle(new DeactivateCustomer(id, "Moved away"), office());
        assertThat(customers.card(id, office()).orElseThrow().status()).isEqualTo("INACTIVE");
        single("CUSTOMER_DEACTIVATED");
        assertThat(kernel.committedEvents()).containsExactly(new CustomerDeactivated(id, SOCIETY));
    }

    // ---- the till's account tenders (27A 6.2) ------------------------------------------------------

    @Test
    void aChargeBeyondTheLimitIsPostedAndFlaggedNeverRefused() {
        UUID accountId = accountFor("Mala Weerasinghe", "0700000110", "190000000003", "2000");

        UUID first = charge(accountId, "1500.00", LocalDate.of(2026, 9, 1));
        assertThat(kernel.committedAudit()).extracting(AuditRecord::eventType).containsExactly("ACCOUNT_CHARGED");
        kernel.reset();

        UUID second = charge(accountId, "800.00", LocalDate.of(2026, 9, 2));
        assertThat(kernel.committedAudit())
                .extracting(AuditRecord::eventType)
                .containsExactly("ACCOUNT_CHARGED", "ACCOUNT_LIMIT_BREACH");
        assertThat(kernel.committedEvents()).singleElement().isInstanceOfSatisfying(AccountCharged.class, e -> {
            assertThat(e.postingId()).isEqualTo(second);
            assertThat(e.limitBreached()).isTrue();
            assertThat(e.balance()).isEqualByComparingTo("2300.00");
        });
        assertThat(accounts.account(accountId, office()).orElseThrow().balance())
                .isEqualByComparingTo("2300.00");
        assertThat(first).isNotEqualTo(second);

        // The same tender delivered again posts nothing more.
        kernel.reset();
        UUID again = tender.handle(
                new PostAccountTender(
                        PostAccountTender.CHARGE,
                        accountId,
                        new BigDecimal("800.00"),
                        receiptOf(second),
                        "RCT-2",
                        1,
                        SHOP,
                        LocalDate.of(2026, 9, 2),
                        null,
                        false),
                till());
        assertThat(again).isEqualTo(second);
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void aTenderForAnAccountTheSocietyDoesNotHaveIsReviewedAndNotPosted() {
        UUID receipt = UUID.randomUUID();
        UUID posted = tender.handle(
                new PostAccountTender(
                        PostAccountTender.CHARGE,
                        UUID.randomUUID(),
                        new BigDecimal("100.00"),
                        receipt,
                        "RCT-X",
                        1,
                        SHOP,
                        LocalDate.of(2026, 9, 3),
                        null,
                        true),
                till());
        assertThat(posted).isNull();
        assertThat(single("ACCOUNT_TENDER_UNKNOWN").subject().id()).isEqualTo(receipt);
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- repayments and allocation (27A 6.3) -------------------------------------------------------

    @Test
    void aRepaymentSettlesTheOldestChargesFirstAndKeepsTheRestAsCredit() {
        UUID accountId = accountFor("Upali Wickramasinghe", "0700000111", "190000000004", "20000");
        UUID older = charge(accountId, "1000.00", LocalDate.of(2026, 8, 1));
        UUID newer = charge(accountId, "700.00", LocalDate.of(2026, 8, 20));
        kernel.reset();

        UUID cpr = pay.handle(
                new RecordCustomerPayment(accountId, "CASH", new BigDecimal("1300.00"), "At the office", null, null),
                office());

        AccountQueries.Payment payment = accounts.payment(cpr, office()).orElseThrow();
        assertThat(payment.docNumber()).isEqualTo("M7S-CPR-0000001");
        assertThat(payment.allocated()).isEqualByComparingTo("1300.00");
        AccountQueries.Statement statement = statement(accountId);
        assertThat(settled(statement, older)).isEqualByComparingTo("1000.00");
        assertThat(settled(statement, newer)).isEqualByComparingTo("300.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("400.00");
        assertThat(kernel.committedAudit())
                .extracting(AuditRecord::eventType)
                .contains("DOCUMENT_ISSUED", "CUSTOMER_PAYMENT_RECORDED");
        assertThat(kernel.committedEvents())
                .filteredOn(CustomerPaymentRecorded.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(CustomerPaymentRecorded.class, e -> {
                    assertThat(e.docNumber()).isEqualTo("M7S-CPR-0000001");
                    assertThat(e.unallocated()).isEqualByComparingTo("0");
                    assertThat(e.balance()).isEqualByComparingTo("400.00");
                });

        // An overpayment is never refused: what is left stays on the account as a credit.
        pay.handle(
                new RecordCustomerPayment(accountId, "TRANSFER", new BigDecimal("1000.00"), null, null, null),
                office());
        AccountView account = accounts.account(accountId, office()).orElseThrow();
        assertThat(account.balance()).isEqualByComparingTo("-600.00");
        assertThat(account.unallocated()).isEqualByComparingTo("600.00");
        assertThat(account.oldestUnpaid()).isNull();
    }

    @Test
    void aSpecificAllocationIsCheckedAgainstWhatIsOpen() {
        UUID accountId = accountFor("Renuka Amarasekara", "0700000112", "190000000005", "20000");
        UUID older = charge(accountId, "1000.00", LocalDate.of(2026, 8, 1));
        UUID newer = charge(accountId, "700.00", LocalDate.of(2026, 8, 20));

        assertThatThrownBy(() -> pay.handle(specific(accountId, "500.00", newer, "800.00"), office()))
                .hasMessageContaining("m7.payment.exceeds_open");
        assertThatThrownBy(() -> pay.handle(specific(accountId, "500.00", newer, "600.00"), office()))
                .hasMessageContaining("m7.payment.exceeds_amount");
        assertThatThrownBy(() -> pay.handle(specific(accountId, "500.00", UUID.randomUUID(), "100.00"), office()))
                .hasMessageContaining("m7.payment.charge_invalid");
        kernel.reset();

        pay.handle(specific(accountId, "700.00", newer, "700.00"), office());
        AccountQueries.Statement statement = statement(accountId);
        assertThat(settled(statement, newer)).isEqualByComparingTo("700.00");
        assertThat(settled(statement, older)).isEqualByComparingTo("0");
        assertThat(accounts.account(accountId, office()).orElseThrow().oldestUnpaid())
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void anOfficeRepaymentIsRecordedEntityWide() {
        UUID accountId = accountFor("Sarath Kumara", "0700000113", "190000000006", "5000");
        assertThatThrownBy(() -> pay.handle(
                        new RecordCustomerPayment(accountId, "CASH", BigDecimal.TEN, null, null, null),
                        lk.coopfed.knoweb.kernel.api.ScopeContext.dev(CustomersFixture.OFFICE_USER, SOCIETY, SHOP)))
                .hasMessageContaining("m7.scope.entity_required");
    }

    /**
     * The ledger's property (AGENTS.md): under a random sequence of charges and repayments the
     * balance is the sum of the postings, no charge is settled beyond its amount, and what the
     * payments hold unallocated is what they paid less what they settled.
     */
    @Test
    void theBalanceIsTheSumOfThePostingsUnderRandomSequences() {
        UUID accountId = accountFor("Nadeesha Liyanage", "0700000114", "190000000007", "50000");
        Random random = new Random(27);
        BigDecimal charged = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        LocalDate day = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < 40; i++) {
            day = day.plusDays(random.nextInt(3));
            if (random.nextInt(3) < 2) {
                BigDecimal amount =
                        BigDecimal.valueOf(50 + random.nextInt(3000)).setScale(2);
                charge(accountId, amount.toPlainString(), day);
                charged = charged.add(amount);
            } else {
                BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(2500)).setScale(2);
                pay.handle(new RecordCustomerPayment(accountId, "CASH", amount, null, null, null), office());
                paid = paid.add(amount);
            }
        }
        AccountView account = accounts.account(accountId, office()).orElseThrow();
        assertThat(account.balance()).isEqualByComparingTo(charged.subtract(paid));
        BigDecimal sum = superuserJdbc()
                .queryForObject(
                        "select sum(amount) from customers.account_posting where account_id = ?",
                        BigDecimal.class,
                        accountId);
        assertThat(account.balance()).isEqualByComparingTo(sum);
        Integer overSettled = superuserJdbc()
                .queryForObject(
                        """
                select count(*) from customers.account_posting p
                 where p.account_id = ? and p.kind = 'CHARGE'
                   and p.amount < (select coalesce(sum(a.amount), 0) from customers.allocation a
                                    where a.charge_posting_id = p.posting_id)
                """,
                        Integer.class,
                        accountId);
        assertThat(overSettled).isZero();
        BigDecimal allocated = superuserJdbc()
                .queryForObject("select coalesce(sum(amount), 0) from customers.allocation", BigDecimal.class);
        assertThat(account.unallocated()).isEqualByComparingTo(paid.subtract(allocated));
        BigDecimal open = account.ageing()
                .days0To30()
                .add(account.ageing().days31To60())
                .add(account.ageing().days61To90())
                .add(account.ageing().over90());
        assertThat(open).isEqualByComparingTo(charged.subtract(allocated));
    }

    // ---- row-level security ----------------------------------------------------------------------

    @Test
    void anotherSocietySeesTheIdentityOnlyWhenItHoldsAnAccountAndNeverTheBalance() {
        UUID id = registerMember("Ayesha Siddique", "0700000115", false);
        UUID accountId = open.handle(new OpenAccount(id, new BigDecimal("5000"), null, null, "190000000008"), office());
        charge(accountId, "900.00", LocalDate.of(2026, 9, 1));

        assertThat(customers.card(id, other())).isEmpty();
        assertThat(customers.search("Ayesha", null, 10, other())).isEmpty();
        assertThat(accounts.account(accountId, other())).isEmpty();
        assertThat(accounts.statement(accountId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), other()))
                .isEmpty();

        // The other society opens an account of its own for the customer (written as the superuser:
        // the cross-society registration path comes with the till): the identity becomes readable,
        // this society's balance does not.
        superuserJdbc()
                .update(
                        """
                insert into customers.customer_account (account_id, customer_id, account_no, credit_limit, opened_at,
                    opened_by, owner_entity_id)
                values (?, ?, 'A00001', 0, now(), ?, ?)
                """,
                        UUID.randomUUID(),
                        id,
                        CustomersFixture.OTHER_USER,
                        OTHER);
        CustomerCard seen = customers.card(id, other()).orElseThrow();
        assertThat(seen.phone()).isEqualTo("+94700000115");
        assertThat(seen.registeredHere()).isFalse();
        assertThat(seen.account().balance()).isEqualByComparingTo("0");
        assertThat(accounts.account(accountId, other())).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private UUID registerMember(String name, String phone, boolean confirmed) {
        return register.handle(command(name, phone, confirmed), office());
    }

    private static RegisterCustomer command(String name, String phone, boolean confirmed) {
        return new RegisterCustomer(
                name,
                null,
                null,
                "si",
                phone,
                List.of("CREDIT_ACCOUNT"),
                "PAPER",
                Map.of(),
                List.of("regular"),
                confirmed);
    }

    private UUID accountFor(String name, String phone, String nic, String limit) {
        UUID id = registerMember(name, phone, false);
        UUID accountId = open.handle(new OpenAccount(id, new BigDecimal(limit), null, null, nic), office());
        kernel.reset();
        return accountId;
    }

    private final List<UUID> receipts = new ArrayList<>();

    /** A till's ACCOUNT tender of a sale on that day at the shop: one receipt each. */
    private UUID charge(UUID accountId, String amount, LocalDate day) {
        UUID receipt = UUID.randomUUID();
        receipts.add(receipt);
        UUID posting = tender.handle(
                new PostAccountTender(
                        PostAccountTender.CHARGE,
                        accountId,
                        new BigDecimal(amount),
                        receipt,
                        "RCT-" + receipts.size(),
                        1,
                        SHOP,
                        day,
                        null,
                        false),
                till());
        postingReceipts.put(posting, receipt);
        return posting;
    }

    private final Map<UUID, UUID> postingReceipts = new java.util.HashMap<>();

    private UUID receiptOf(UUID posting) {
        return postingReceipts.get(posting);
    }

    private static RecordCustomerPayment specific(UUID accountId, String amount, UUID charge, String toCharge) {
        return new RecordCustomerPayment(
                accountId,
                "CASH",
                new BigDecimal(amount),
                null,
                RecordCustomerPayment.SPECIFIC,
                List.of(new RecordCustomerPayment.Specific(charge, new BigDecimal(toCharge))));
    }

    private AccountQueries.Statement statement(UUID accountId) {
        return accounts.statement(accountId, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31), office())
                .orElseThrow();
    }

    private static BigDecimal settled(AccountQueries.Statement statement, UUID posting) {
        return statement.lines().stream()
                .filter(line -> line.postingId().equals(posting))
                .findFirst()
                .orElseThrow()
                .settled();
    }

    private AuditRecord single(String type) {
        List<AuditRecord> found = kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(type))
                .toList();
        assertThat(found).as(type).hasSize(1);
        return found.get(0);
    }
}
