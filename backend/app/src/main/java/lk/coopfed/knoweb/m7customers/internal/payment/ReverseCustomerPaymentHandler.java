package lk.coopfed.knoweb.m7customers.internal.payment;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentLinks;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m7customers.api.CustomerPaymentReversed;
import lk.coopfed.knoweb.m7customers.api.ReverseCustomerPayment;
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
 * ReverseCustomerPayment (27A section 6; doc 27 sections 3.5 and 4.3: "A recorded payment is
 * reversed by a REVERSES-linked CPR (wrong customer, bounced deposit); allocations are undone;
 * MFA; reason"). Guards, in order: the society's entity-wide OWN scope; the CPR, visible to the
 * society ({@code m7.payment.not_found}); not itself a reversal ({@code m7.payment.not_reversible});
 * not reversed before ({@code m7.payment.reversed_already}); a reason; its account not CLOSED
 * ({@code m7.account.closed}). The second factor is the permission's ({@code cus.payment.reverse}
 * requires MFA in the catalogue), checked by the kernel before the handler runs.
 *
 * <p>Mutation: a reversing CPR from the society's ENTITY series with the original as its
 * reference, linked REVERSES to it in the kernel when the original is a kernel document (an office
 * CPR; a till's CPR is kept by M7 alone, so the link is {@code reversal_of} only); its
 * {@code doc_customer_payment} row naming the original; a REVERSAL posting of the payment's amount
 * (positive: the customer owes it again); one {@code allocation_reversal} row per allocation the
 * payment made, so the charges it settled are open again; the account's other unallocated credits
 * re-applied to them oldest first (CR-27A-1 item 2); the balance recomputed. Audit
 * CUSTOMER_PAYMENT_REVERSED with the reason; event customer_payment.reversed.v1.
 */
@Service
@CommandHandler(permission = "cus.payment.reverse")
class ReverseCustomerPaymentHandler implements Handles<ReverseCustomerPayment, UUID> {

    static final String AUDIT_REVERSED = "CUSTOMER_PAYMENT_REVERSED";

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final DocumentLinks links;
    private final NumberingService numbering;
    private final PartyQueries parties;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one handler specification
    ReverseCustomerPaymentHandler(
            JdbcTemplate jdbc,
            Ledger ledger,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            DocumentLinks links,
            NumberingService numbering,
            PartyQueries parties,
            CustomersClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.documents = documents;
        this.issuance = issuance;
        this.links = links;
        this.numbering = numbering;
        this.parties = parties;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    /** The CPR being reversed. */
    private record Original(
            UUID documentId, UUID accountId, String method, BigDecimal amount, UUID reversalOf, String origin) {}

    @Override
    @Transactional
    public UUID handle(ReverseCustomerPayment command, ScopeContext scope) {
        if (command == null || command.documentId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireEntityWideScope(scope);
        Original original = jdbc
                .query(
                        """
                        select document_id, account_id, method, amount, reversal_of, origin
                          from customers.doc_customer_payment where document_id = ?
                        """,
                        (rs, n) -> new Original(
                                rs.getObject("document_id", UUID.class),
                                rs.getObject("account_id", UUID.class),
                                rs.getString("method"),
                                rs.getBigDecimal("amount"),
                                rs.getObject("reversal_of", UUID.class),
                                rs.getString("origin")),
                        command.documentId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException("m7.payment.not_found"));
        if (original.reversalOf() != null) {
            throw new ProblemException("m7.payment.not_reversible");
        }
        // The account's lock serialises two reversals of one receipt (the unique index on
        // reversal_of is the safety net): the second sees the first's row.
        LockedAccount account =
                ledger.lock(original.accountId()).orElseThrow(() -> new ProblemException("m7.account.not_found"));
        Integer reversed = jdbc.queryForObject(
                "select count(*) from customers.doc_customer_payment where reversal_of = ?",
                Integer.class,
                original.documentId());
        if (reversed != null && reversed > 0) {
            throw new ProblemException("m7.payment.reversed_already");
        }
        // The reason goes onto the reversing CPR (an issued document) and into the audit: no phone
        // number or NIC in it (wave 2, M7CR-10).
        String reason = PersonalDataText.require(CustomerGuards.requiredText(command.reason(), "reason"), "reason");
        if ("CLOSED".equals(account.status())) {
            throw new ProblemException("m7.account.closed");
        }

        // The reversing CPR: a draft naming the original, its one line, the society's series.
        UUID society = scope.entityId();
        UUID reversalId = Ids.next();
        documents.save(draft(reversalId, society, scope.userId(), original.documentId(), reason));
        documents.saveLines(
                reversalId, List.of(RecordCustomerPaymentHandler.amountLine(reversalId, original.amount())));
        String entityCode = parties.getEntity(society, scope)
                .map(EntityView::entityCode)
                .orElseThrow(() -> new ProblemException("m7.scope.own_required"));
        numbering.registerSeries(
                SeriesRegistration.forEntity(RecordCustomerPaymentHandler.CPR, society, entityCode), scope);
        DocumentRecord issued = issuance.issue(
                documents.findByIdForUpdate(reversalId).orElseThrow(), documents.findLines(reversalId), scope);
        if ("OFFICE".equals(original.origin())) {
            links.link(reversalId, original.documentId(), LinkType.REVERSES, null, scope);
        }
        jdbc.update(
                """
                insert into customers.doc_customer_payment (document_id, account_id, method, reference, amount,
                    allocation_mode, reversal_of, origin, doc_number_display, owner_entity_id)
                values (?, ?, ?, ?, ?, 'OLDEST_FIRST', ?, 'OFFICE', ?, ?)
                """,
                reversalId,
                account.accountId(),
                original.method(),
                reason,
                original.amount(),
                original.documentId(),
                issued.docNumberDisplay(),
                society);

        UUID reversalPostingId = Ids.next();
        jdbc.update(
                """
                insert into customers.account_posting (posting_id, account_id, kind, amount, business_date, document_id,
                    receipt_number, operator_user_id, owner_entity_id)
                values (?, ?, 'REVERSAL', ?, ?, ?, ?, ?, ?)
                """,
                reversalPostingId,
                account.accountId(),
                original.amount(),
                issued.businessDate() != null ? issued.businessDate() : clock.today(),
                reversalId,
                issued.docNumberDisplay(),
                scope.userId(),
                society);
        int undone = jdbc.update(
                """
                insert into customers.allocation_reversal (allocation_id, reversal_posting_id, owner_entity_id)
                select a.allocation_id, ?, a.owner_entity_id
                  from customers.allocation a
                  join customers.account_posting p on p.posting_id = a.payment_posting_id
                 where p.document_id = ? and p.kind = 'PAYMENT' and"""
                        + Ledger.LIVE_ALLOCATION,
                reversalPostingId,
                original.documentId());
        // The charges the payment settled are open again; what the account's other credits still
        // hold (a later payment left unallocated, a refund) settles them oldest first, so the
        // ageing shows what is really owed (wave 2, CR-27A-1 item 2).
        int reapplied = 0;
        for (Ledger.OpenCredit credit : ledger.unallocatedCredits(account.accountId())) {
            List<Allocator.Allocation> allocations =
                    Allocator.oldestFirst(ledger.openCharges(account.accountId()), credit.open());
            for (Allocator.Allocation allocation : allocations) {
                jdbc.update(
                        Ledger.ALLOCATION_INSERT,
                        Ids.next(),
                        credit.postingId(),
                        allocation.chargePostingId(),
                        allocation.amount(),
                        account.ownerEntityId());
            }
            reapplied += allocations.size();
        }
        BigDecimal balance = ledger.sum(account.accountId());
        jdbc.update(
                "update customers.customer_account set balance = ? where account_id = ?", balance, account.accountId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reversalDocNumber", issued.docNumberDisplay());
        after.put("reversedDocumentId", original.documentId());
        after.put("accountId", account.accountId());
        after.put("amount", original.amount());
        after.put("allocationsUndone", undone);
        after.put("allocationsReapplied", reapplied);
        after.put("balance", balance);
        audit.record(
                AUDIT_REVERSED,
                Subject.of("customer_payment", original.documentId()),
                Map.of("balance", account.balance()),
                after,
                scope,
                reason);
        events.publish(new CustomerPaymentReversed(
                original.documentId(),
                reversalId,
                issued.docNumberDisplay(),
                account.accountId(),
                account.customerId(),
                society,
                original.amount(),
                balance));
        return reversalId;
    }

    /** A draft reversing CPR: no location and no till (the ENTITY series); the original as its reference. */
    private static DocumentRecord draft(UUID id, UUID owner, UUID operator, UUID original, String notes) {
        return new DocumentRecord(
                id,
                RecordCustomerPaymentHandler.CPR,
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
                original,
                null,
                DocumentOrigin.ONLINE,
                null,
                notes);
    }
}
