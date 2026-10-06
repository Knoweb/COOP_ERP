package lk.coopfed.knoweb.m7customers.web;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.api.AmendAccountLimits;
import lk.coopfed.knoweb.m7customers.api.ApproveAdjustment;
import lk.coopfed.knoweb.m7customers.api.ChangeAccountStatus;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.RecordCustomerPayment;
import lk.coopfed.knoweb.m7customers.api.RequestAdjustment;
import lk.coopfed.knoweb.m7customers.api.ReverseCustomerPayment;
import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.m7customers.query.AccountView;
import lk.coopfed.knoweb.m7customers.web.generated.Account;
import lk.coopfed.knoweb.m7customers.web.generated.AccountHistoryEntry;
import lk.coopfed.knoweb.m7customers.web.generated.AccountsApi;
import lk.coopfed.knoweb.m7customers.web.generated.Adjustment;
import lk.coopfed.knoweb.m7customers.web.generated.AdjustmentRequest;
import lk.coopfed.knoweb.m7customers.web.generated.AmendLimitsRequest;
import lk.coopfed.knoweb.m7customers.web.generated.CustomerPayment;
import lk.coopfed.knoweb.m7customers.web.generated.OpenAccountRequest;
import lk.coopfed.knoweb.m7customers.web.generated.ReasonRequest;
import lk.coopfed.knoweb.m7customers.web.generated.RecordPaymentRequest;
import lk.coopfed.knoweb.m7customers.web.generated.Statement;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The society's credit book (27A section 5): accounts, statements and repayments at the office. */
@RestController
class AccountController implements AccountsApi {

    /** A statement covers at most a year: longer is a report, not a screen. */
    static final int STATEMENT_DAYS_MAX = 366;

    private final Handles<OpenAccount, UUID> open;
    private final Handles<RecordCustomerPayment, UUID> recordPayment;
    private final Handles<AmendAccountLimits, UUID> amendLimits;
    private final Handles<ChangeAccountStatus, UUID> changeStatus;
    private final Handles<RequestAdjustment, UUID> requestAdjustment;
    private final Handles<ApproveAdjustment, UUID> approveAdjustment;
    private final Handles<ReverseCustomerPayment, UUID> reverse;
    private final AccountQueries accounts;
    private final CurrentScope currentScope;

    @SuppressWarnings("java:S107") // one controller for the slice's Accounts tag
    AccountController(
            Handles<OpenAccount, UUID> open,
            Handles<RecordCustomerPayment, UUID> recordPayment,
            Handles<AmendAccountLimits, UUID> amendLimits,
            Handles<ChangeAccountStatus, UUID> changeStatus,
            Handles<RequestAdjustment, UUID> requestAdjustment,
            Handles<ApproveAdjustment, UUID> approveAdjustment,
            Handles<ReverseCustomerPayment, UUID> reverse,
            AccountQueries accounts,
            CurrentScope currentScope) {
        this.open = open;
        this.recordPayment = recordPayment;
        this.amendLimits = amendLimits;
        this.changeStatus = changeStatus;
        this.requestAdjustment = requestAdjustment;
        this.approveAdjustment = approveAdjustment;
        this.reverse = reverse;
        this.accounts = accounts;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<Account> openAccount(String idempotencyKey, UUID customerId, OpenAccountRequest request) {
        ScopeContext scope = currentScope.get();
        UUID accountId = open.handle(
                new OpenAccount(
                        customerId,
                        request.getCreditLimit(),
                        request.getTermsDays(),
                        request.getOfflineCap(),
                        request.getNic()),
                scope);
        return ResponseEntity.created(URI.create("/v1/accounts/" + accountId))
                .body(CustomerResponses.account(
                        accounts.account(accountId, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<Account> getAccount(UUID accountId) {
        return accounts.account(accountId, currentScope.get())
                .map(CustomerResponses::account)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<Statement> getStatement(LocalDate from, LocalDate to, UUID accountId) {
        if (to.isBefore(from) || from.plusDays(STATEMENT_DAYS_MAX).isBefore(to)) {
            throw new ProblemException("m7.statement.period_invalid");
        }
        return accounts.statement(accountId, from, to, currentScope.get())
                .map(CustomerResponses::statement)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<CustomerPayment> recordCustomerPayment(
            String idempotencyKey, UUID accountId, RecordPaymentRequest request) {
        ScopeContext scope = currentScope.get();
        UUID documentId = recordPayment.handle(
                new RecordCustomerPayment(
                        accountId,
                        request.getMethod().getValue(),
                        request.getAmount(),
                        request.getReference(),
                        request.getAllocationMode() == null
                                ? null
                                : request.getAllocationMode().getValue(),
                        request.getSpecific() == null
                                ? null
                                : request.getSpecific().stream()
                                        .map(s -> new RecordCustomerPayment.Specific(
                                                s.getChargePostingId(), s.getAmount()))
                                        .toList()),
                scope);
        AccountView account = accounts.account(accountId, scope).orElseThrow();
        return ResponseEntity.created(URI.create("/v1/customer-payments/" + documentId))
                .body(payment(documentId, account));
    }

    @Override
    public ResponseEntity<Account> amendAccountLimits(
            String idempotencyKey, UUID accountId, AmendLimitsRequest request) {
        ScopeContext scope = currentScope.get();
        amendLimits.handle(
                new AmendAccountLimits(
                        accountId,
                        request.getCreditLimit(),
                        request.getHardBlock(),
                        request.getOfflineCap(),
                        request.getReason(),
                        request.getNic()),
                scope);
        return ResponseEntity.ok(
                CustomerResponses.account(accounts.account(accountId, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<Account> suspendAccount(String idempotencyKey, UUID accountId, ReasonRequest request) {
        return status(accountId, ChangeAccountStatus.SUSPEND, request);
    }

    @Override
    public ResponseEntity<Account> reinstateAccount(String idempotencyKey, UUID accountId, ReasonRequest request) {
        return status(accountId, ChangeAccountStatus.REINSTATE, request);
    }

    @Override
    public ResponseEntity<Account> closeAccount(String idempotencyKey, UUID accountId, ReasonRequest request) {
        return status(accountId, ChangeAccountStatus.CLOSE, request);
    }

    @Override
    public ResponseEntity<Account> reopenAccount(String idempotencyKey, UUID accountId, ReasonRequest request) {
        return status(accountId, ChangeAccountStatus.REOPEN, request);
    }

    private ResponseEntity<Account> status(UUID accountId, String action, ReasonRequest request) {
        ScopeContext scope = currentScope.get();
        changeStatus.handle(new ChangeAccountStatus(accountId, action, request.getReason()), scope);
        return ResponseEntity.ok(
                CustomerResponses.account(accounts.account(accountId, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<List<AccountHistoryEntry>> getAccountHistory(UUID accountId) {
        return ResponseEntity.ok(accounts.history(accountId, currentScope.get()).stream()
                .map(CustomerResponses::history)
                .toList());
    }

    @Override
    public ResponseEntity<List<Adjustment>> listAdjustments(UUID accountId) {
        return ResponseEntity.ok(accounts.adjustments(accountId, currentScope.get()).stream()
                .map(CustomerResponses::adjustment)
                .toList());
    }

    @Override
    public ResponseEntity<Adjustment> requestAdjustment(
            String idempotencyKey, UUID accountId, AdjustmentRequest request) {
        ScopeContext scope = currentScope.get();
        UUID adjustmentId = requestAdjustment.handle(
                new RequestAdjustment(accountId, request.getAmount(), request.getReason()), scope);
        return ResponseEntity.created(URI.create("/v1/accounts/" + accountId + "/adjustments/" + adjustmentId))
                .body(adjustment(accountId, adjustmentId, scope));
    }

    @Override
    public ResponseEntity<Adjustment> approveAccountAdjustment(
            String idempotencyKey, UUID accountId, UUID adjustmentId) {
        ScopeContext scope = currentScope.get();
        approveAdjustment.handle(new ApproveAdjustment(adjustmentId), scope);
        return ResponseEntity.ok(adjustment(accountId, adjustmentId, scope));
    }

    private Adjustment adjustment(UUID accountId, UUID adjustmentId, ScopeContext scope) {
        return accounts.adjustments(accountId, scope).stream()
                .filter(a -> a.adjustmentId().equals(adjustmentId))
                .findFirst()
                .map(CustomerResponses::adjustment)
                .orElseThrow(() -> new ProblemException("m7.adjustment.not_found"));
    }

    @Override
    public ResponseEntity<CustomerPayment> reverseCustomerPayment(
            String idempotencyKey, UUID documentId, ReasonRequest request) {
        ScopeContext scope = currentScope.get();
        UUID reversalId = reverse.handle(new ReverseCustomerPayment(documentId, request.getReason()), scope);
        AccountQueries.Payment reversal = accounts.payment(reversalId, scope).orElseThrow();
        AccountView account = accounts.account(reversal.accountId(), scope).orElseThrow();
        return ResponseEntity.created(URI.create("/v1/customer-payments/" + reversalId))
                .body(payment(reversalId, account));
    }

    private CustomerPayment payment(UUID documentId, AccountView account) {
        AccountQueries.Payment payment =
                accounts.payment(documentId, currentScope.get()).orElseThrow();
        return new CustomerPayment()
                .documentId(documentId)
                .docNumber(payment.docNumber())
                .accountId(payment.accountId())
                .amount(payment.amount())
                .allocated(payment.allocated())
                .unallocated(payment.amount().subtract(payment.allocated()))
                .balance(account.balance());
    }
}
