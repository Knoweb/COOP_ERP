package lk.coopfed.knoweb.m7customers.web;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.RecordCustomerPayment;
import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.m7customers.query.AccountView;
import lk.coopfed.knoweb.m7customers.web.generated.Account;
import lk.coopfed.knoweb.m7customers.web.generated.AccountsApi;
import lk.coopfed.knoweb.m7customers.web.generated.CustomerPayment;
import lk.coopfed.knoweb.m7customers.web.generated.OpenAccountRequest;
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
    private final AccountQueries accounts;
    private final CurrentScope currentScope;

    AccountController(
            Handles<OpenAccount, UUID> open,
            Handles<RecordCustomerPayment, UUID> recordPayment,
            AccountQueries accounts,
            CurrentScope currentScope) {
        this.open = open;
        this.recordPayment = recordPayment;
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
