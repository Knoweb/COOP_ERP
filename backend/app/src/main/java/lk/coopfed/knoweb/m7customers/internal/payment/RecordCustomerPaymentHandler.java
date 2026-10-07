package lk.coopfed.knoweb.m7customers.internal.payment;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m7customers.api.CustomerPaymentRecorded;
import lk.coopfed.knoweb.m7customers.api.RecordCustomerPayment;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.customer.PersonalDataText;
import lk.coopfed.knoweb.m7customers.internal.ledger.Allocator;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger.LockedAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordCustomerPayment at the society office (27A section 6). Guards, in order: the society's
 * entity-wide OWN scope (a repayment at the office concerns no shop, and the receipt takes the
 * society's ENTITY series); the account of this society, OPEN or SUSPENDED; a known method; an
 * amount above zero in cents; a known allocation mode; with SPECIFIC, the chosen charges open
 * charges of this account, each no more than is open and together no more than the payment
 * ({@link Allocator#specific}); a reference free of a phone number or NIC ({@code
 * m7.field.personal_data}: an issued CPR is retained after an erasure).
 *
 * <p>Mutation: the CPR from the society's ENTITY series (27A: "ENTITY series in the office"; the
 * type's TILL_POSITION scope is the till's, and the numbering falls back to the entity's series
 * when the header names no till), its one amount line and {@code doc_customer_payment}; the
 * PAYMENT posting (negative); one allocation row per charge settled (oldest first, or as chosen);
 * the balance recomputed as the sum of the postings. What is not allocated stays on the account
 * as a credit. Audit CUSTOMER_PAYMENT_RECORDED (the kernel audits DOCUMENT_ISSUED as well); event
 * customer_payment.recorded.v1.
 */
@Service
@CommandHandler(permission = "cus.payment.record")
class RecordCustomerPaymentHandler implements Handles<RecordCustomerPayment, UUID> {

    static final String CPR = "CPR";
    static final String AUDIT_RECORDED = "CUSTOMER_PAYMENT_RECORDED";
    static final Set<String> METHODS = Set.of("CASH", "TRANSFER", "DEPOSIT");

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final NumberingService numbering;
    private final PartyQueries parties;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one handler specification
    RecordCustomerPaymentHandler(
            JdbcTemplate jdbc,
            Ledger ledger,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            NumberingService numbering,
            PartyQueries parties,
            CustomersClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.documents = documents;
        this.issuance = issuance;
        this.numbering = numbering;
        this.parties = parties;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordCustomerPayment command, ScopeContext scope) {
        if (command == null || command.accountId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireEntityWideScope(scope);
        LockedAccount account =
                ledger.lock(command.accountId()).orElseThrow(() -> new ProblemException("m7.account.not_found"));
        if (!"OPEN".equals(account.status()) && !"SUSPENDED".equals(account.status())) {
            throw new ProblemException("m7.account.not_open", Map.of("status", account.status()));
        }
        String method = CustomerGuards.requiredText(command.method(), "method");
        if (!METHODS.contains(method)) {
            throw new ProblemException("m7.payment.method_invalid", Map.of("method", method));
        }
        BigDecimal amount = command.amount();
        if (amount == null
                || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new ProblemException("m7.payment.amount_invalid");
        }
        amount = amount.setScale(2);
        String mode = command.allocationMode() == null ? RecordCustomerPayment.OLDEST_FIRST : command.allocationMode();
        List<Allocator.OpenCharge> open = ledger.openCharges(account.accountId());
        List<Allocator.Allocation> allocations;
        if (RecordCustomerPayment.OLDEST_FIRST.equals(mode)) {
            allocations = Allocator.oldestFirst(open, amount);
        } else if (RecordCustomerPayment.SPECIFIC.equals(mode)) {
            allocations = Allocator.specific(
                    open,
                    command.specific().stream()
                            .map(s -> s == null ? null : new Allocator.Allocation(s.chargePostingId(), s.amount()))
                            .toList(),
                    amount);
        } else {
            throw new ProblemException("m7.payment.mode_invalid", Map.of("allocationMode", mode));
        }
        BigDecimal allocated =
                allocations.stream().map(Allocator.Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // The reference stays on the issued CPR after an erasure: no phone number or NIC in it.
        String reference = PersonalDataText.require(CustomerGuards.blankToNull(command.reference()), "reference");

        // The CPR: a draft, its one line, the society's ENTITY series, the issuance.
        UUID documentId = Ids.next();
        UUID society = scope.entityId();
        documents.save(draft(documentId, society, scope.userId(), reference));
        documents.saveLines(documentId, List.of(amountLine(documentId, amount)));
        String entityCode = parties.getEntity(society, scope)
                .map(EntityView::entityCode)
                .orElseThrow(() -> new ProblemException("m7.scope.own_required"));
        numbering.registerSeries(SeriesRegistration.forEntity(CPR, society, entityCode), scope);
        DocumentRecord issued = issuance.issue(
                documents.findByIdForUpdate(documentId).orElseThrow(), documents.findLines(documentId), scope);
        jdbc.update(
                """
                insert into customers.doc_customer_payment (document_id, account_id, method, reference, amount,
                    allocation_mode, doc_number_display, owner_entity_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                documentId,
                account.accountId(),
                method,
                reference,
                amount,
                mode,
                issued.docNumberDisplay(),
                society);

        UUID paymentPostingId = Ids.next();
        jdbc.update(
                """
                insert into customers.account_posting (posting_id, account_id, kind, amount, business_date, document_id,
                    receipt_number, operator_user_id, owner_entity_id)
                values (?, ?, 'PAYMENT', ?, ?, ?, ?, ?, ?)
                """,
                paymentPostingId,
                account.accountId(),
                amount.negate(),
                issued.businessDate() != null ? issued.businessDate() : clock.today(),
                documentId,
                issued.docNumberDisplay(),
                scope.userId(),
                society);
        for (Allocator.Allocation allocation : allocations) {
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
        }
        BigDecimal balance = ledger.sum(account.accountId());
        jdbc.update(
                "update customers.customer_account set balance = ? where account_id = ?", balance, account.accountId());

        BigDecimal unallocated = amount.subtract(allocated);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("docNumber", issued.docNumberDisplay());
        after.put("accountId", account.accountId());
        after.put("method", method);
        after.put("amount", amount);
        after.put("allocationMode", mode);
        after.put("allocated", allocated);
        after.put("unallocated", unallocated);
        after.put("charges", allocations.size());
        after.put("balance", balance);
        audit.record(
                AUDIT_RECORDED,
                Subject.of("customer_payment", documentId),
                Map.of("balance", account.balance()),
                after,
                scope);
        events.publish(new CustomerPaymentRecorded(
                documentId,
                issued.docNumberDisplay(),
                account.accountId(),
                account.customerId(),
                society,
                amount,
                allocated,
                unallocated,
                balance));
        return documentId;
    }

    /** A draft CPR: no location and no till, so it takes the society's ENTITY series. */
    private static DocumentRecord draft(UUID id, UUID owner, UUID operator, String notes) {
        return new DocumentRecord(
                id,
                CPR,
                null,
                null,
                null,
                owner,
                null,
                null,
                null,
                null,
                "DRAFT",
                null,
                null,
                null,
                operator,
                "LKR",
                null,
                null,
                null,
                null,
                null,
                DocumentOrigin.ONLINE,
                null,
                notes);
    }

    /** The one line of a CPR: no item, quantity one, the amount as its total. */
    static DocumentLineRecord amountLine(UUID documentId, BigDecimal amount) {
        return new DocumentLineRecord(
                Ids.next(),
                documentId,
                1,
                null,
                null,
                null,
                BigDecimal.ONE,
                amount,
                null,
                null,
                null,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                amount,
                null,
                null,
                null);
    }
}
