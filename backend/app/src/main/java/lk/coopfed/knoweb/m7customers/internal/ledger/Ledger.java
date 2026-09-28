package lk.coopfed.knoweb.m7customers.internal.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of the posting ledger the handlers share (27A: "PostingService (insert-only, balance
 * recache)"). It only reads; the handlers write the postings and the balance themselves, since
 * only a command handler writes (AGENTS.md, the architecture tests).
 */
@Component
public class Ledger {

    /** The account row a posting handler locks: its customer, limit, flags and status. */
    public record LockedAccount(
            UUID accountId,
            UUID customerId,
            UUID ownerEntityId,
            BigDecimal creditLimit,
            BigDecimal balance,
            boolean hardBlock,
            String status,
            String accountNo) {}

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
                               account_no
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
                                rs.getString("account_no")),
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

    /** The account's charges with something still open, oldest first. */
    public List<Allocator.OpenCharge> openCharges(UUID accountId) {
        return jdbc
                .query(
                        """
                select p.posting_id, p.business_date, p.received_at,
                       p.amount - coalesce((select sum(a.amount) from customers.allocation a
                                             where a.charge_posting_id = p.posting_id), 0) as open
                  from customers.account_posting p
                 where p.account_id = ? and p.kind = 'CHARGE'
                 order by p.business_date, p.received_at
                """,
                        (rs, n) -> new Allocator.OpenCharge(
                                rs.getObject("posting_id", UUID.class),
                                rs.getObject("business_date", java.time.LocalDate.class),
                                rs.getTimestamp("received_at").toInstant(),
                                rs.getBigDecimal("open")),
                        accountId)
                .stream()
                .filter(charge -> charge.open().signum() > 0)
                .toList();
    }
}
