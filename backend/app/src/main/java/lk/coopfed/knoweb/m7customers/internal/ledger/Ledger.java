package lk.coopfed.knoweb.m7customers.internal.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of the posting ledger the handlers share (27A: "PostingService (insert-only, balance
 * recache)"). It only reads; the handlers write the postings, the allocations and the balance
 * themselves, since only a command handler writes (AGENTS.md, the architecture tests).
 *
 * <p>The two sides of the ledger (wave 2, M7CR-08; CR-27A-1 item 2). The <b>charge side</b> is a
 * CHARGE or a positive ADJUSTMENT; what is open of it is its amount less its live allocations. The
 * <b>credit side</b> is a PAYMENT, a REVERSAL, a CREDIT (a refund or a void) or a negative
 * ADJUSTMENT; every one of them but the REVERSAL settles charges through {@code allocation} rows,
 * exactly as a payment does. So {@code Σ open charges − unallocated = Σ charge side + Σ credit
 * side = balance} by construction, which {@code AccountRemaindersIntegrationTest} checks under
 * random interleavings.
 */
@Component
public class Ledger {

    /**
     * An allocation still in force: one a reversal of its payment has not undone (27A section 6:
     * "allocations reversed"). Every sum of allocations adds this condition on alias {@code a}.
     */
    public static final String LIVE_ALLOCATION =
            " not exists (select 1 from customers.allocation_reversal r where r.allocation_id = a.allocation_id) ";

    /** The credit side of the ledger, on alias {@code p}. */
    public static final String CREDIT_SIDE =
            " (p.kind in ('PAYMENT', 'REVERSAL', 'CREDIT') or (p.kind = 'ADJUSTMENT' and p.amount < 0)) ";

    /** The charge side of the ledger, on alias {@code p}. */
    public static final String CHARGE_SIDE = " (p.kind = 'CHARGE' or (p.kind = 'ADJUSTMENT' and p.amount > 0)) ";

    /** One allocation row, for the handlers: allocation id, the settling posting, the charge, the amount, the owner. */
    public static final String ALLOCATION_INSERT =
            """
            insert into customers.allocation (allocation_id, payment_posting_id, charge_posting_id, amount, owner_entity_id)
            values (?, ?, ?, ?, ?)
            """;

    /** The account row a posting handler locks: its customer, limit, flags and status. */
    public record LockedAccount(
            UUID accountId,
            UUID customerId,
            UUID ownerEntityId,
            BigDecimal creditLimit,
            BigDecimal balance,
            boolean hardBlock,
            String status,
            String accountNo,
            BigDecimal offlineCap) {}

    /**
     * A credit-side posting with something not yet allocated to a charge: a payment that was not
     * reversed, a refund or void, a negative adjustment. {@code open} is what it still holds.
     */
    public record OpenCredit(
            UUID postingId, UUID documentId, LocalDate businessDate, Instant receivedAt, BigDecimal open) {}

    private final JdbcTemplate jdbc;

    Ledger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The account, locked for the transaction; empty when it does not exist or the scope may not see it. */
    public Optional<LockedAccount> lock(UUID accountId) {
        return jdbc
                .query(
                        """
                        select account_id, customer_id, owner_entity_id, credit_limit, balance, hard_block, status,
                               account_no, offline_cap
                          from customers.customer_account where account_id = ? for update
                        """,
                        (rs, n) -> new LockedAccount(
                                rs.getObject("account_id", UUID.class),
                                rs.getObject("customer_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getBigDecimal("credit_limit"),
                                rs.getBigDecimal("balance"),
                                rs.getBoolean("hard_block"),
                                rs.getString("status"),
                                rs.getString("account_no"),
                                rs.getBigDecimal("offline_cap")),
                        accountId)
                .stream()
                .findFirst();
    }

    /** The balance as the sum of the account's postings: what the cache is set to after every posting. */
    public BigDecimal sum(UUID accountId) {
        BigDecimal sum = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from customers.account_posting where account_id = ?",
                BigDecimal.class,
                accountId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * What the credit side holds beyond the charges it settled: minus the sum of the credit side
     * (payments and their reversals, refunds and voids, negative adjustments), less the
     * allocations still in force.
     */
    public BigDecimal unallocated(UUID accountId) {
        BigDecimal credited = jdbc.queryForObject(
                "select coalesce(-sum(p.amount), 0) from customers.account_posting p where p.account_id = ? and"
                        + CREDIT_SIDE,
                BigDecimal.class,
                accountId);
        BigDecimal allocated = jdbc.queryForObject(
                """
                select coalesce(sum(a.amount), 0) from customers.allocation a
                  join customers.account_posting p on p.posting_id = a.payment_posting_id
                 where p.account_id = ? and"""
                        + LIVE_ALLOCATION,
                BigDecimal.class,
                accountId);
        return (credited == null ? BigDecimal.ZERO : credited)
                .subtract(allocated == null ? BigDecimal.ZERO : allocated);
    }

    /**
     * The account's charges with something still open, oldest first. An approved adjustment that
     * adds to the balance is settled like a charge.
     */
    public List<Allocator.OpenCharge> openCharges(UUID accountId) {
        return jdbc
                .query(
                        """
                        select p.posting_id, p.document_id, p.business_date, p.received_at,
                               p.amount - coalesce((select sum(a.amount) from customers.allocation a
                                                     where a.charge_posting_id = p.posting_id and"""
                                + LIVE_ALLOCATION
                                + """
                                ), 0) as open
                          from customers.account_posting p
                         where p.account_id = ? and"""
                                + CHARGE_SIDE
                                + " order by p.business_date, p.received_at",
                        (rs, n) -> new Allocator.OpenCharge(
                                rs.getObject("posting_id", UUID.class),
                                rs.getObject("document_id", UUID.class),
                                rs.getObject("business_date", LocalDate.class),
                                rs.getTimestamp("received_at").toInstant(),
                                rs.getBigDecimal("open")),
                        accountId)
                .stream()
                .filter(charge -> charge.open().signum() > 0)
                .toList();
    }

    /**
     * The account's credit-side postings with something not yet allocated, oldest first: what a
     * reversal re-applies to the charges it opened again (CR-27A-1 item 2). A reversed payment is
     * not one of them: its allocations were undone and its reversal cancels it.
     */
    public List<OpenCredit> unallocatedCredits(UUID accountId) {
        return jdbc
                .query(
                        """
                        select p.posting_id, p.document_id, p.business_date, p.received_at,
                               -p.amount - coalesce((select sum(a.amount) from customers.allocation a
                                                      where a.payment_posting_id = p.posting_id and"""
                                + LIVE_ALLOCATION
                                + """
                                ), 0) as open
                          from customers.account_posting p
                         where p.account_id = ?
                           and (p.kind = 'CREDIT'
                                or (p.kind = 'ADJUSTMENT' and p.amount < 0)
                                or (p.kind = 'PAYMENT' and not exists (select 1 from customers.doc_customer_payment r
                                                                         where r.reversal_of = p.document_id)))
                         order by p.business_date, p.received_at
                        """,
                        (rs, n) -> new OpenCredit(
                                rs.getObject("posting_id", UUID.class),
                                rs.getObject("document_id", UUID.class),
                                rs.getObject("business_date", LocalDate.class),
                                rs.getTimestamp("received_at").toInstant(),
                                rs.getBigDecimal("open")),
                        accountId)
                .stream()
                .filter(credit -> credit.open().signum() > 0)
                .toList();
    }
}
