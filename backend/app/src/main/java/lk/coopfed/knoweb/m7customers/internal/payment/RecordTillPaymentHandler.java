package lk.coopfed.knoweb.m7customers.internal.payment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.CustomerPaymentRecorded;
import lk.coopfed.knoweb.m7customers.internal.ledger.Allocator;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger.LockedAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CprBundleHook of 27A sections 4 and 7.3: a repayment a till took, offline or not, applied
 * centrally. The till numbered the CPR from its own series and the cash is in its drawer, so the
 * fact is never refused for a business reason (AGENTS.md; doc 27 section 3.5, DR-2): what central
 * finds odd is a flag on the CPR and a REVIEW audit record ({@code TILL_PAYMENT_FLAGGED}):
 * <ul>
 *   <li>{@code CLOSED_ACCOUNT}: posted all the same (27A: "a CPR against a CLOSED account posts
 *       and raises REVIEW (cash was taken)");</li>
 *   <li>{@code UNKNOWN_ACCOUNT}: the account is not the society's (or does not exist); the CPR is
 *       kept with no posting, for the society to follow up;</li>
 *   <li>{@code METHOD_UNKNOWN}: kept as CASH, the only method a till takes;</li>
 *   <li>{@code LOCATION_MISMATCH}: the CPR names another shop than the device's.</li>
 * </ul>
 *
 * <p>Guards (the shape a till cannot send if it follows the contract): the device's OWN scope at its
 * shop ({@code m7.scope.device_required}); a document id, an account id, an amount above zero in
 * cents and a business date ({@code m7.till_payment.malformed}). A CPR applied before is not
 * applied again (the consumer's inbox is the first guard against a redelivery).
 *
 * <p>Mutation: {@code doc_customer_payment} with origin TILL, the till's number and series; the
 * series' high-water mark raised ({@link NumberingService#observeDeviceNumber}); the PAYMENT
 * posting at the CPR's business date, allocated oldest first (27A: "CprBundleHook -> PAYMENT
 * posting + Allocator"); the balance recomputed. Audit CUSTOMER_PAYMENT_RECORDED (and
 * TILL_PAYMENT_FLAGGED when flagged); event customer_payment.recorded.v1. Permission: the system
 * applies it for the till with no user, so none is checked; the code is the till's own.
 */
@Service
@CommandHandler(permission = "cus.payment.record")
class RecordTillPaymentHandler implements Handles<RecordTillPayment, UUID> {

    static final String AUDIT_RECORDED = RecordCustomerPaymentHandler.AUDIT_RECORDED;
    static final String AUDIT_FLAGGED = "TILL_PAYMENT_FLAGGED";

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final NumberingService numbering;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordTillPaymentHandler(
            JdbcTemplate jdbc, Ledger ledger, NumberingService numbering, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.numbering = numbering;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordTillPayment command, ScopeContext scope) {
        if (scope == null
                || scope.policyClass() != PolicyClass.OWN
                || scope.entityId() == null
                || scope.locationId() == null) {
            throw new ProblemException("m7.scope.device_required");
        }
        if (command == null
                || command.documentId() == null
                || command.accountId() == null
                || command.businessDate() == null
                || command.amount() == null
                || command.amount().signum() <= 0
                || command.amount().stripTrailingZeros().scale() > 2) {
            throw new ProblemException("m7.till_payment.malformed");
        }
        Integer applied = jdbc.queryForObject(
                "select count(*) from customers.doc_customer_payment where document_id = ?",
                Integer.class,
                command.documentId());
        if (applied != null && applied > 0) {
            return command.documentId();
        }

        UUID society = scope.entityId();
        UUID shop = scope.locationId();
        BigDecimal amount = command.amount().setScale(2);
        List<String> flags = new ArrayList<>();
        String method = command.method();
        if (!RecordCustomerPaymentHandler.METHODS.contains(method)) {
            flags.add("METHOD_UNKNOWN");
            method = "CASH";
        }
        if (command.locationId() != null && !command.locationId().equals(shop)) {
            flags.add("LOCATION_MISMATCH");
        }
        LockedAccount account = ledger.lock(command.accountId()).orElse(null);
        if (account == null) {
            flags.add("UNKNOWN_ACCOUNT");
        } else if ("CLOSED".equals(account.status())) {
            flags.add("CLOSED_ACCOUNT");
        }
        if (command.seriesId() != null && command.docNumber() != null) {
            // Only the device's own series at its shop moves (wave 2, M6-04, the kernel's rule).
            numbering.observeDeviceNumber(command.seriesId(), command.docNumber(), scope);
        }

        jdbc.update(
                """
                insert into customers.doc_customer_payment (document_id, account_id, method, reference, amount,
                    allocation_mode, origin, doc_number_display, series_id, doc_number, taken_at_location_id, device_id,
                    flags, owner_entity_id)
                values (?, ?, ?, ?, ?, 'OLDEST_FIRST', 'TILL', ?, ?, ?, ?, ?, ?::text[], ?)
                """,
                command.documentId(),
                command.accountId(),
                method,
                command.reference(),
                amount,
                command.docNumberDisplay(),
                command.seriesId(),
                command.docNumber(),
                shop,
                scope.deviceId() != null ? scope.deviceId() : command.deviceId(),
                "{" + String.join(",", flags) + "}",
                society);

        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal balance = null;
        if (account != null) {
            UUID paymentPostingId = Ids.next();
            jdbc.update(
                    """
                    insert into customers.account_posting (posting_id, account_id, kind, amount, business_date, document_id,
                        receipt_number, sold_at_location_id, operator_user_id, offline, owner_entity_id)
                    values (?, ?, 'PAYMENT', ?, ?, ?, ?, ?, ?, false, ?)
                    """,
                    paymentPostingId,
                    account.accountId(),
                    amount.negate(),
                    command.businessDate(),
                    command.documentId(),
                    command.docNumberDisplay(),
                    shop,
                    command.operatorUserId(),
                    society);
            for (Allocator.Allocation allocation :
                    Allocator.oldestFirst(ledger.openCharges(account.accountId()), amount)) {
                jdbc.update(
                        """
                        insert into customers.allocation (allocation_id, payment_posting_id, charge_posting_id, amount,
                            owner_entity_id)
                        values (?, ?, ?, ?, ?)
                        """,
                        Ids.next(),
                        paymentPostingId,
                        allocation.chargePostingId(),
                        allocation.amount(),
                        society);
                allocated = allocated.add(allocation.amount());
            }
            balance = ledger.sum(account.accountId());
            jdbc.update(
                    "update customers.customer_account set balance = ? where account_id = ?",
                    balance,
                    account.accountId());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("docNumber", command.docNumberDisplay());
        after.put("origin", "TILL");
        after.put("accountId", command.accountId());
        after.put("method", method);
        after.put("amount", amount);
        after.put("allocated", allocated);
        after.put("balance", balance);
        Subject subject = Subject.of("customer_payment", command.documentId());
        audit.record(AUDIT_RECORDED, subject, null, after, scope);
        if (!flags.isEmpty()) {
            audit.record(
                    AUDIT_FLAGGED,
                    subject,
                    null,
                    Map.of("flags", flags, "accountId", command.accountId()),
                    scope,
                    "Applied and flagged: " + String.join(", ", flags));
        }
        events.publish(new CustomerPaymentRecorded(
                command.documentId(),
                command.docNumberDisplay(),
                command.accountId(),
                account == null ? null : account.customerId(),
                society,
                amount,
                allocated,
                amount.subtract(allocated),
                balance));
        return command.documentId();
    }
}
