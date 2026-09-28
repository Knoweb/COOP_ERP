package lk.coopfed.knoweb.m7customers.internal.consumers;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.AccountCharged;
import lk.coopfed.knoweb.m7customers.api.AccountCredited;
import lk.coopfed.knoweb.m7customers.api.PostAccountTender;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger.LockedAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostCharge / PostCredit (27A section 6.2): one ACCOUNT tender of a till receipt on the customer's
 * account. The till took the sale on its own rules (27A section 7.3); central applies the fact and
 * flags what is odd, never refuses it (AGENTS.md):
 * <ul>
 *   <li>a CHARGE beyond the limit is posted with {@code limit_breached} and a REVIEW
 *       (ACCOUNT_LIMIT_BREACH), and an ALERT (HARD_BLOCK_BYPASSED_OFFLINE) when the account is hard
 *       blocked and the till was offline;</li>
 *   <li>a CHARGE on an account that is SUSPENDED or CLOSED is posted with a REVIEW
 *       (ACCOUNT_CHARGED_NOT_OPEN), and an offline CHARGE above the account's offline cap with a
 *       REVIEW (ACCOUNT_OFFLINE_CAP_EXCEEDED): the till's snapshot was stale;</li>
 *   <li>a tender naming an account the society does not have (or not at all) is not posted, and a
 *       REVIEW (ACCOUNT_TENDER_UNKNOWN) names the receipt, so the society can follow it up.</li>
 * </ul>
 * Guards (the shape a till cannot send if it follows the contract): the society's OWN scope; a
 * kind, an account, an amount above zero, the receipt and its business date ({@code
 * m7.tender.malformed}). The same tender posted again (a redelivery) returns the first posting.
 *
 * <p>Mutation: the posting (CHARGE positive, CREDIT negative) at the receipt's shop, the balance
 * recomputed as the sum of the postings. Audit ACCOUNT_CHARGED or ACCOUNT_CREDITED; events
 * account.charged.v1 or account.credited.v1. Permission: the system applies it for the till with
 * no user, so none is checked there; cus.customer.view is the code of the account reads.
 */
@Service
@CommandHandler(permission = "cus.customer.view")
public class PostAccountTenderHandler implements Handles<PostAccountTender, UUID> {

    static final String AUDIT_CHARGED = "ACCOUNT_CHARGED";
    static final String AUDIT_CREDITED = "ACCOUNT_CREDITED";
    static final String AUDIT_BREACH = "ACCOUNT_LIMIT_BREACH";
    static final String AUDIT_BYPASSED = "HARD_BLOCK_BYPASSED_OFFLINE";
    static final String AUDIT_UNKNOWN = "ACCOUNT_TENDER_UNKNOWN";
    static final String AUDIT_NOT_OPEN = "ACCOUNT_CHARGED_NOT_OPEN";
    static final String AUDIT_OVER_CAP = "ACCOUNT_OFFLINE_CAP_EXCEEDED";

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final AuditFacade audit;
    private final EventPublisher events;

    PostAccountTenderHandler(JdbcTemplate jdbc, Ledger ledger, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(PostAccountTender command, ScopeContext scope) {
        CustomerGuards.requireOwnScope(scope);
        if (command == null
                || command.accountId() == null
                || command.receiptDocumentId() == null
                || command.businessDate() == null
                || command.amount() == null
                || command.amount().signum() <= 0
                || !(PostAccountTender.CHARGE.equals(command.kind())
                        || PostAccountTender.CREDIT.equals(command.kind()))) {
            throw new ProblemException("m7.tender.malformed");
        }
        List<UUID> posted = jdbc.queryForList(
                """
                select posting_id from customers.account_posting
                 where document_id = ? and account_id = ? and kind = ? and tender_seq = ?
                """,
                UUID.class,
                command.receiptDocumentId(),
                command.accountId(),
                command.kind(),
                command.tenderSeq());
        if (!posted.isEmpty()) {
            return posted.get(0);
        }

        LockedAccount account = ledger.lock(command.accountId()).orElse(null);
        if (account == null) {
            Map<String, Object> flagged = new LinkedHashMap<>();
            flagged.put("accountId", command.accountId());
            flagged.put("receiptNumber", command.receiptNumber());
            flagged.put("amount", command.amount());
            audit.record(AUDIT_UNKNOWN, Subject.of("document", command.receiptDocumentId()), null, flagged, scope);
            return null;
        }
        boolean charge = PostAccountTender.CHARGE.equals(command.kind());
        BigDecimal amount = charge ? command.amount() : command.amount().negate();
        boolean breach = charge && account.balance().add(amount).compareTo(account.creditLimit()) > 0;

        UUID postingId = Ids.next();
        UUID location = scope.locationId() != null ? scope.locationId() : command.locationId();
        jdbc.update(
                """
                insert into customers.account_posting (posting_id, account_id, kind, amount, business_date, document_id,
                    tender_seq, receipt_number, sold_at_location_id, operator_user_id, offline, limit_breached, owner_entity_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                postingId,
                account.accountId(),
                command.kind(),
                amount,
                command.businessDate(),
                command.receiptDocumentId(),
                command.tenderSeq(),
                command.receiptNumber(),
                location,
                command.operatorUserId(),
                command.offline(),
                breach,
                account.ownerEntityId());
        BigDecimal balance = ledger.sum(account.accountId());
        jdbc.update(
                "update customers.customer_account set balance = ? where account_id = ?", balance, account.accountId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("postingId", postingId);
        after.put("kind", command.kind());
        after.put("amount", amount);
        after.put("receiptNumber", command.receiptNumber());
        after.put("businessDate", command.businessDate());
        after.put("offline", command.offline());
        after.put("balance", balance);
        after.put("limitBreached", breach);
        Subject subject = Subject.of("customer_account", account.accountId());
        audit.record(
                charge ? AUDIT_CHARGED : AUDIT_CREDITED, subject, Map.of("balance", account.balance()), after, scope);
        if (breach) {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("creditLimit", account.creditLimit());
            review.put("balance", balance);
            review.put("offline", command.offline());
            review.put("receiptNumber", command.receiptNumber());
            audit.record(AUDIT_BREACH, subject, null, review, scope);
            if (account.hardBlock() && command.offline()) {
                audit.record(AUDIT_BYPASSED, subject, null, review, scope);
            }
        }
        // The till's own rules (27A section 7.3) should have kept these out; a till with a stale
        // snapshot did not. The sale happened: posted, and flagged for the society.
        if (charge && !"OPEN".equals(account.status())) {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("status", account.status());
            review.put("receiptNumber", command.receiptNumber());
            review.put("amount", amount);
            audit.record(AUDIT_NOT_OPEN, subject, null, review, scope);
        }
        if (charge && command.offline() && account.offlineCap() != null && amount.compareTo(account.offlineCap()) > 0) {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("offlineCap", account.offlineCap());
            review.put("receiptNumber", command.receiptNumber());
            review.put("amount", amount);
            audit.record(AUDIT_OVER_CAP, subject, null, review, scope);
        }
        if (charge) {
            events.publish(new AccountCharged(
                    account.accountId(),
                    account.customerId(),
                    account.ownerEntityId(),
                    postingId,
                    command.receiptDocumentId(),
                    amount,
                    balance,
                    breach,
                    command.offline()));
        } else {
            events.publish(new AccountCredited(
                    account.accountId(),
                    account.customerId(),
                    account.ownerEntityId(),
                    postingId,
                    command.receiptDocumentId(),
                    amount,
                    balance));
        }
        return postingId;
    }
}
