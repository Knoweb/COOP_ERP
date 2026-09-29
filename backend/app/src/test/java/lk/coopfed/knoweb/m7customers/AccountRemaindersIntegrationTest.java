package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.SECOND_CLERK;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.office;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.officeWithMfa;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.other;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.till;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.withMfa;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.api.AccountAdjusted;
import lk.coopfed.knoweb.m7customers.api.AccountClosed;
import lk.coopfed.knoweb.m7customers.api.AccountLimitsAmended;
import lk.coopfed.knoweb.m7customers.api.AccountReinstated;
import lk.coopfed.knoweb.m7customers.api.AccountSuspended;
import lk.coopfed.knoweb.m7customers.api.AdjustmentRequested;
import lk.coopfed.knoweb.m7customers.api.AmendAccountLimits;
import lk.coopfed.knoweb.m7customers.api.ApproveAdjustment;
import lk.coopfed.knoweb.m7customers.api.ChangeAccountStatus;
import lk.coopfed.knoweb.m7customers.api.CustomerPaymentReversed;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.PostAccountTender;
import lk.coopfed.knoweb.m7customers.api.RecordCustomerPayment;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.m7customers.api.RequestAdjustment;
import lk.coopfed.knoweb.m7customers.api.ReverseCustomerPayment;
import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.m7customers.query.AccountView;
import lk.coopfed.knoweb.testsupport.KernelRecorder.AuditRecord;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The account's remainders (27A section 6): limits, hard block and offline cap with the step-up on
 * a higher limit; suspend, reinstate and close with their history; a till's charge on a suspended
 * account or over the offline cap posted and flagged, never refused; the reversal of a repayment
 * (its allocations undone); adjustments with separation of duties; and the ledger's property with
 * reversals and adjustments in the mix. Business dates are fixed dates, never today's.
 */
class AccountRemaindersIntegrationTest extends PostgresIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    @Autowired
    Handles<RegisterCustomer, UUID> register;

    @Autowired
    Handles<OpenAccount, UUID> open;

    @Autowired
    Handles<PostAccountTender, UUID> tender;

    @Autowired
    Handles<RecordCustomerPayment, UUID> pay;

    @Autowired
    Handles<AmendAccountLimits, UUID> amendLimits;

    @Autowired
    Handles<ChangeAccountStatus, UUID> changeStatus;

    @Autowired
    Handles<ReverseCustomerPayment, UUID> reverse;

    @Autowired
    Handles<RequestAdjustment, UUID> requestAdjustment;

    @Autowired
    Handles<ApproveAdjustment, UUID> approveAdjustment;

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

    // ---- limits, hard block, offline cap ---------------------------------------------------------

    @Test
    void aHigherLimitAsksForAFreshSecondFactorALowerOneDoesNot() {
        UUID accountId = account("0700000401", "190000000401", "10000");

        assertThatThrownBy(() -> amendLimits.handle(
                        new AmendAccountLimits(accountId, new BigDecimal("20000"), null, null, "Pays on time"),
                        office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("mfa.required"));
        ScopeContext stale = withMfa(office(), Instant.now().minusSeconds(3600));
        assertThatThrownBy(() -> amendLimits.handle(
                        new AmendAccountLimits(accountId, new BigDecimal("20000"), null, null, "Pays on time"), stale))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("mfa.required"));
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        amendLimits.handle(
                new AmendAccountLimits(
                        accountId, new BigDecimal("20000"), true, new BigDecimal("2500"), "Pays on time"),
                officeWithMfa());
        AuditRecord audit = single("ACCOUNT_LIMIT_AMENDED");
        assertThat(audit.reason()).isEqualTo("Pays on time");
        assertThat(String.valueOf(audit.before())).contains("10000");
        assertThat(String.valueOf(audit.after())).contains("20000", "2500");
        assertThat(kernel.committedEvents()).singleElement().isInstanceOfSatisfying(AccountLimitsAmended.class, e -> {
            assertThat(e.creditLimit()).isEqualByComparingTo("20000");
            assertThat(e.hardBlock()).isTrue();
            assertThat(e.offlineCap()).isEqualByComparingTo("2500");
        });
        kernel.reset();

        // Lower: no second factor needed. The same values again: nothing to change.
        amendLimits.handle(
                new AmendAccountLimits(accountId, new BigDecimal("5000"), null, null, "Missed a month"), office());
        assertThatThrownBy(() -> amendLimits.handle(
                        new AmendAccountLimits(accountId, new BigDecimal("5000"), true, null, "Again"), office()))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m7.account.limits_unchanged"));
        assertThatThrownBy(() -> amendLimits.handle(
                        new AmendAccountLimits(accountId, new BigDecimal("4000"), null, null, " "), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.field.required"));
        assertThatThrownBy(() -> amendLimits.handle(
                        new AmendAccountLimits(accountId, new BigDecimal("4000.005"), null, null, "Cents"), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.account.amount_invalid"));
        // Another society does not see the account at all.
        assertThatThrownBy(() -> amendLimits.handle(
                        new AmendAccountLimits(accountId, BigDecimal.ONE, null, null, "Not ours"), other()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.account.not_found"));

        AccountView view = accounts.account(accountId, office()).orElseThrow();
        assertThat(view.creditLimit()).isEqualByComparingTo("5000");
        assertThat(view.hardBlock()).isTrue();
        assertThat(accounts.history(accountId, office()))
                .extracting(AccountQueries.HistoryEntry::action)
                .containsExactly("LIMITS_AMENDED", "LIMITS_AMENDED");
    }

    @Test
    void aTillChargeOnASuspendedAccountOrOverTheOfflineCapIsPostedAndFlagged() {
        UUID accountId = account("0700000402", "190000000402", "10000");
        amendLimits.handle(new AmendAccountLimits(accountId, null, null, new BigDecimal("1000"), "Cap"), office());
        changeStatus.handle(new ChangeAccountStatus(accountId, ChangeAccountStatus.SUSPEND, "Overdue"), office());
        kernel.reset();

        tender.handle(
                new PostAccountTender(
                        PostAccountTender.CHARGE,
                        accountId,
                        new BigDecimal("1500.00"),
                        UUID.randomUUID(),
                        "RCT-9",
                        1,
                        SHOP,
                        DAY,
                        null,
                        true),
                till());

        assertThat(accounts.account(accountId, office()).orElseThrow().balance())
                .isEqualByComparingTo("1500");
        assertThat(kernel.committedAudit())
                .extracting(AuditRecord::eventType)
                .contains("ACCOUNT_CHARGED", "ACCOUNT_CHARGED_NOT_OPEN", "ACCOUNT_OFFLINE_CAP_EXCEEDED")
                .doesNotContain("ACCOUNT_LIMIT_BREACH");
    }

    // ---- suspend, reinstate, close ----------------------------------------------------------------

    @Test
    void suspendReinstateAndCloseFollowTheStatesAndCloseOnlyAtZero() {
        UUID accountId = account("0700000403", "190000000403", "10000");

        assertThatThrownBy(() -> changeStatus.handle(
                        new ChangeAccountStatus(accountId, ChangeAccountStatus.REINSTATE, "Not suspended"), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.account.status_invalid"));
        assertThatThrownBy(() -> changeStatus.handle(new ChangeAccountStatus(accountId, "FREEZE", "No"), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.account.action_invalid"));

        changeStatus.handle(new ChangeAccountStatus(accountId, ChangeAccountStatus.SUSPEND, "Overdue"), office());
        assertThat(single("ACCOUNT_SUSPENDED").reason()).isEqualTo("Overdue");
        assertThat(kernel.committedEvents()).singleElement().isInstanceOf(AccountSuspended.class);
        assertThat(accounts.account(accountId, office()).orElseThrow().status()).isEqualTo("SUSPENDED");
        kernel.reset();

        changeStatus.handle(new ChangeAccountStatus(accountId, ChangeAccountStatus.REINSTATE, "Paid up"), office());
        assertThat(kernel.committedEvents()).singleElement().isInstanceOf(AccountReinstated.class);
        kernel.reset();

        // A balance: the account does not close. A payment beyond the charge: money held, still no.
        charge(accountId, "800.00");
        assertThatThrownBy(() -> changeStatus.handle(
                        new ChangeAccountStatus(accountId, ChangeAccountStatus.CLOSE, "Moving away"), office()))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m7.account.balance_not_zero"));
        pay.handle(payment(accountId, "800.00"), office());
        kernel.reset();

        changeStatus.handle(new ChangeAccountStatus(accountId, ChangeAccountStatus.CLOSE, "Moving away"), office());
        assertThat(single("ACCOUNT_CLOSED").reason()).isEqualTo("Moving away");
        assertThat(kernel.committedEvents()).singleElement().isInstanceOf(AccountClosed.class);
        assertThat(accounts.history(accountId, office()))
                .extracting(AccountQueries.HistoryEntry::action)
                .containsExactly("CLOSED", "REINSTATED", "SUSPENDED");
        assertThatThrownBy(() -> amendLimits.handle(
                        new AmendAccountLimits(accountId, BigDecimal.ONE, null, null, "Closed"), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.account.closed"));
    }

    @Test
    void anAccountHoldingAnAdvancePaymentDoesNotClose() {
        UUID accountId = account("0700000404", "190000000404", "10000");
        pay.handle(payment(accountId, "500.00"), office());
        UUID adjustment = requestAdjustment.handle(
                new RequestAdjustment(accountId, new BigDecimal("500.00"), "Fee agreed"), office());
        approveAdjustment.handle(new ApproveAdjustment(adjustment), clerk());
        // The balance is zero now, but the payment is not allocated to the adjustment it preceded.
        assertThat(accounts.account(accountId, office()).orElseThrow().balance())
                .isEqualByComparingTo("0");
        assertThatThrownBy(() -> changeStatus.handle(
                        new ChangeAccountStatus(accountId, ChangeAccountStatus.CLOSE, "Done"), office()))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m7.account.unallocated_held"));
    }

    // ---- reversal -----------------------------------------------------------------------------------

    @Test
    void aReversalUndoesTheAllocationsAndCannotBeRepeated() {
        UUID accountId = account("0700000405", "190000000405", "10000");
        UUID first = charge(accountId, "1000.00");
        charge(accountId, "500.00");
        UUID paymentId = pay.handle(payment(accountId, "1200.00"), office());
        assertThat(accounts.account(accountId, office()).orElseThrow().balance())
                .isEqualByComparingTo("300");
        kernel.reset();

        UUID reversalId = reverse.handle(new ReverseCustomerPayment(paymentId, "Bounced deposit"), office());

        AccountView after = accounts.account(accountId, office()).orElseThrow();
        assertThat(after.balance()).isEqualByComparingTo("1500");
        assertThat(after.unallocated()).isEqualByComparingTo("0");
        assertThat(after.ageing()
                        .days0To30()
                        .add(after.ageing().days31To60())
                        .add(after.ageing().days61To90())
                        .add(after.ageing().over90()))
                .isEqualByComparingTo("1500");
        AccountQueries.Statement statement = statement(accountId);
        assertThat(statement.lines())
                .filteredOn(line -> line.postingId().equals(first))
                .singleElement()
                .satisfies(line -> assertThat(line.settled()).isEqualByComparingTo("0"));
        assertThat(statement.lines())
                .filteredOn(line -> "PAYMENT".equals(line.kind()))
                .singleElement()
                .satisfies(line -> assertThat(line.reversed()).isTrue());
        assertThat(statement.lines())
                .filteredOn(line -> "REVERSAL".equals(line.kind()))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.amount()).isEqualByComparingTo("1200");
                    assertThat(line.documentNumber()).isEqualTo("M7S-CPR-0000002");
                });
        assertThat(accounts.payment(paymentId, office()).orElseThrow().reversedBy())
                .isEqualTo(reversalId);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.document_link where from_document_id = ? and to_document_id = ? and link_type = 'REVERSES'",
                                Integer.class,
                                reversalId,
                                paymentId))
                .isEqualTo(1);

        AuditRecord audit = single("CUSTOMER_PAYMENT_REVERSED");
        assertThat(audit.reason()).isEqualTo("Bounced deposit");
        assertThat(String.valueOf(audit.after())).contains("allocationsUndone=2");
        assertThat(kernel.committedEvents())
                .filteredOn(CustomerPaymentReversed.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(CustomerPaymentReversed.class, e -> {
                    assertThat(e.reversalDocumentId()).isEqualTo(reversalId);
                    assertThat(e.balance()).isEqualByComparingTo("1500");
                });
        kernel.reset();

        assertThatThrownBy(() -> reverse.handle(new ReverseCustomerPayment(paymentId, "Again"), office()))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m7.payment.reversed_already"));
        assertThatThrownBy(() -> reverse.handle(new ReverseCustomerPayment(reversalId, "Undo"), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.payment.not_reversible"));
        assertThatThrownBy(() -> reverse.handle(new ReverseCustomerPayment(UUID.randomUUID(), "None"), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.payment.not_found"));
        assertThat(kernel.committedAudit()).isEmpty();

        // A new payment settles the charges again, oldest first.
        pay.handle(payment(accountId, "1000.00"), office());
        assertThat(settled(statement(accountId), first)).isEqualByComparingTo("1000");
    }

    // ---- adjustments ----------------------------------------------------------------------------------

    @Test
    void anAdjustmentIsPostedOnlyWhenAnotherPersonApprovesIt() {
        UUID accountId = account("0700000406", "190000000406", "10000");
        charge(accountId, "1000.00");
        kernel.reset();

        assertThatThrownBy(() -> requestAdjustment.handle(
                        new RequestAdjustment(accountId, BigDecimal.ZERO, "Nothing"), office()))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m7.adjustment.amount_invalid"));
        UUID adjustment = requestAdjustment.handle(
                new RequestAdjustment(accountId, new BigDecimal("-250.00"), "Damaged goods returned"), office());
        assertThat(single("ADJUSTMENT_REQUESTED").reason()).isEqualTo("Damaged goods returned");
        assertThat(kernel.committedEvents()).singleElement().isInstanceOf(AdjustmentRequested.class);
        assertThat(accounts.account(accountId, office()).orElseThrow().balance())
                .isEqualByComparingTo("1000");
        kernel.reset();

        assertThatThrownBy(() -> approveAdjustment.handle(new ApproveAdjustment(adjustment), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.adjustment.same_person"));
        assertThat(kernel.committedAudit()).isEmpty();

        UUID posting = approveAdjustment.handle(new ApproveAdjustment(adjustment), clerk());
        assertThat(accounts.account(accountId, office()).orElseThrow().balance())
                .isEqualByComparingTo("750");
        assertThat(single("ACCOUNT_ADJUSTED").reason()).isEqualTo("Damaged goods returned");
        assertThat(kernel.committedEvents()).singleElement().isInstanceOfSatisfying(AccountAdjusted.class, e -> {
            assertThat(e.postingId()).isEqualTo(posting);
            assertThat(e.balance()).isEqualByComparingTo("750");
        });
        assertThat(accounts.adjustments(accountId, office())).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo("APPROVED");
            assertThat(a.approvedBy()).isEqualTo(SECOND_CLERK);
        });
        assertThatThrownBy(() -> approveAdjustment.handle(new ApproveAdjustment(adjustment), clerk()))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m7.adjustment.not_requested"));
    }

    // ---- the ledger's property ------------------------------------------------------------------

    /**
     * 27A section 9: "Balance = Σ postings under random sequences incl. reversals; Σ allocations per
     * charge ≤ charge; unallocated payment amount consistent".
     */
    @Test
    void theBalanceIsTheSumOfThePostingsWithReversalsAndAdjustments() {
        UUID accountId = account("0700000407", "190000000407", "100000");
        Random random = new Random(29);
        List<UUID> payments = new ArrayList<>();
        for (int step = 0; step < 40; step++) {
            int move = random.nextInt(10);
            if (move < 5) {
                charge(accountId, (100 + random.nextInt(2000)) + ".00");
            } else if (move < 8) {
                payments.add(pay.handle(payment(accountId, (50 + random.nextInt(1500)) + ".50"), office()));
            } else if (move == 8 && !payments.isEmpty()) {
                UUID paymentId = payments.remove(random.nextInt(payments.size()));
                reverse.handle(new ReverseCustomerPayment(paymentId, "Random reversal"), office());
            } else {
                UUID adjustment = requestAdjustment.handle(
                        new RequestAdjustment(accountId, new BigDecimal((random.nextInt(600) - 300) + ".25"), "Random"),
                        office());
                approveAdjustment.handle(new ApproveAdjustment(adjustment), clerk());
            }
            BigDecimal sum = superuserJdbc()
                    .queryForObject(
                            "select coalesce(sum(amount), 0) from customers.account_posting where account_id = ?",
                            BigDecimal.class,
                            accountId);
            AccountView view = accounts.account(accountId, office()).orElseThrow();
            assertThat(view.balance()).as("step " + step).isEqualByComparingTo(sum);
            Integer overAllocated = superuserJdbc()
                    .queryForObject(
                            """
                            select count(*) from customers.account_posting p
                             where p.account_id = ? and p.amount > 0 and p.kind in ('CHARGE', 'ADJUSTMENT')
                               and p.amount < (select coalesce(sum(a.amount), 0) from customers.allocation a
                                                where a.charge_posting_id = p.posting_id
                                                  and not exists (select 1 from customers.allocation_reversal r
                                                                   where r.allocation_id = a.allocation_id))
                            """,
                            Integer.class,
                            accountId);
            assertThat(overAllocated).as("step " + step).isZero();
            assertThat(view.unallocated().signum()).as("step " + step).isGreaterThanOrEqualTo(0);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private ScopeContext clerk() {
        return withMfa(ScopeContext.dev(SECOND_CLERK, SOCIETY, null), Instant.now());
    }

    private UUID account(String phone, String nic, String limit) {
        UUID id = register.handle(
                new RegisterCustomer(
                        "Member " + phone.substring(phone.length() - 3),
                        null,
                        null,
                        "si",
                        phone,
                        List.of("CREDIT_ACCOUNT"),
                        "PAPER",
                        Map.of(),
                        List.of(),
                        false),
                office());
        UUID accountId = open.handle(new OpenAccount(id, new BigDecimal(limit), null, null, nic), office());
        kernel.reset();
        return accountId;
    }

    private int receipts;

    private UUID charge(UUID accountId, String amount) {
        receipts++;
        return tender.handle(
                new PostAccountTender(
                        PostAccountTender.CHARGE,
                        accountId,
                        new BigDecimal(amount),
                        UUID.randomUUID(),
                        "RCT-" + receipts,
                        1,
                        SHOP,
                        DAY.plusDays(receipts % 20),
                        null,
                        false),
                till());
    }

    private static RecordCustomerPayment payment(UUID accountId, String amount) {
        return new RecordCustomerPayment(
                accountId, "CASH", new BigDecimal(amount), null, RecordCustomerPayment.OLDEST_FIRST, List.of());
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
