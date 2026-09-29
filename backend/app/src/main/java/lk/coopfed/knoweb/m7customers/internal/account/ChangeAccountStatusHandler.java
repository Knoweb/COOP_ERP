package lk.coopfed.knoweb.m7customers.internal.account;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.AccountClosed;
import lk.coopfed.knoweb.m7customers.api.AccountReinstated;
import lk.coopfed.knoweb.m7customers.api.AccountSuspended;
import lk.coopfed.knoweb.m7customers.api.ChangeAccountStatus;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger.LockedAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SuspendAccount / ReinstateAccount / CloseAccount (27A section 6; doc 27 section 4.2). Guards, in
 * order: the society's OWN scope; the account of this society; a known action ({@code
 * m7.account.action_invalid}); a reason; the transition: SUSPEND from OPEN, REINSTATE from
 * SUSPENDED, CLOSE from OPEN or SUSPENDED ({@code m7.account.status_invalid}); to close, a balance of
 * zero ({@code m7.account.balance_not_zero}) and nothing held from payments ({@code
 * m7.account.unallocated_held}).
 *
 * <p>Suspending changes what the till accepts (27A section 7.3: ACCOUNT tender only when OPEN)
 * but never what central posts: a till's charge on a suspended or closed account is posted and
 * flagged like any other (AGENTS.md). Mutation: the status; a row in the account's history. Audit
 * ACCOUNT_SUSPENDED, ACCOUNT_REINSTATED or ACCOUNT_CLOSED with the reason; events
 * account.suspended.v1, account.reinstated.v1 or account.closed.v1.
 */
@Service
@CommandHandler(permission = "cus.account.manage")
class ChangeAccountStatusHandler implements Handles<ChangeAccountStatus, UUID> {

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ChangeAccountStatusHandler(
            JdbcTemplate jdbc, Ledger ledger, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    /** What an action does: the states it starts from, where it leads, and how it is recorded. */
    private record Transition(String from1, String from2, String to, String history, String auditCode) {

        boolean startsFrom(String status) {
            return from1.equals(status) || from2.equals(status);
        }
    }

    private static final Map<String, Transition> TRANSITIONS = Map.of(
            ChangeAccountStatus.SUSPEND,
            new Transition("OPEN", "OPEN", "SUSPENDED", "SUSPENDED", "ACCOUNT_SUSPENDED"),
            ChangeAccountStatus.REINSTATE,
            new Transition("SUSPENDED", "SUSPENDED", "OPEN", "REINSTATED", "ACCOUNT_REINSTATED"),
            ChangeAccountStatus.CLOSE,
            new Transition("OPEN", "SUSPENDED", "CLOSED", "CLOSED", "ACCOUNT_CLOSED"));

    @Override
    @Transactional
    public UUID handle(ChangeAccountStatus command, ScopeContext scope) {
        if (command == null || command.accountId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        LockedAccount account =
                ledger.lock(command.accountId()).orElseThrow(() -> new ProblemException("m7.account.not_found"));
        Transition transition = command.action() == null ? null : TRANSITIONS.get(command.action());
        if (transition == null) {
            throw new ProblemException("m7.account.action_invalid", Map.of("action", String.valueOf(command.action())));
        }
        String reason = CustomerGuards.requiredText(command.reason(), "reason");
        if (!transition.startsFrom(account.status())) {
            throw new ProblemException(
                    "m7.account.status_invalid", Map.of("status", account.status(), "action", command.action()));
        }
        if (ChangeAccountStatus.CLOSE.equals(command.action())) {
            BigDecimal balance = ledger.sum(account.accountId());
            if (balance.signum() != 0) {
                throw new ProblemException("m7.account.balance_not_zero", Map.of("balance", balance.toPlainString()));
            }
            if (ledger.unallocated(account.accountId()).signum() != 0) {
                throw new ProblemException("m7.account.unallocated_held");
            }
        }

        jdbc.update(
                "update customers.customer_account set status = ? where account_id = ?",
                transition.to(),
                account.accountId());
        jdbc.update(
                """
                insert into customers.account_history (history_id, account_id, action, before_value, after_value, reason,
                    changed_by, changed_at, owner_entity_id)
                values (?, ?, ?, jsonb_build_object('status', ?::text), jsonb_build_object('status', ?::text), ?, ?, ?, ?)
                """,
                Ids.next(),
                account.accountId(),
                transition.history(),
                account.status(),
                transition.to(),
                reason,
                scope.userId(),
                Timestamp.from(clock.now()),
                account.ownerEntityId());

        audit.record(
                transition.auditCode(),
                Subject.of("customer_account", account.accountId()),
                Map.of("status", account.status()),
                Map.of("status", transition.to()),
                scope,
                reason);
        DomainEvent event =
                switch (command.action()) {
                    case ChangeAccountStatus.SUSPEND ->
                        new AccountSuspended(account.accountId(), account.customerId(), account.ownerEntityId());
                    case ChangeAccountStatus.REINSTATE ->
                        new AccountReinstated(account.accountId(), account.customerId(), account.ownerEntityId());
                    default -> new AccountClosed(account.accountId(), account.customerId(), account.ownerEntityId());
                };
        events.publish(event);
        return account.accountId();
    }
}
