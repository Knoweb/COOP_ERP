package lk.coopfed.knoweb.m7customers.internal.queries;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.internal.ledger.Allocator;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger;
import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.m7customers.query.AccountView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The credit book, read under the caller's scope (row-level security filters; nothing here names
 * an entity). The balance is the cache the handlers recompute; the ageing is computed on read from
 * the open charges (27A section 7, AgeingCalculator: "bucket by today − business_date on the
 * unallocated amount").
 */
@Service
@Transactional(readOnly = true)
class AccountQueriesImpl implements AccountQueries {

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final CustomersClock clock;

    private final ObjectMapper json;

    AccountQueriesImpl(JdbcTemplate jdbc, Ledger ledger, CustomersClock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    public Optional<AccountView> account(UUID accountId, ScopeContext scope) {
        record Row(
                UUID accountId,
                String accountNo,
                UUID customerId,
                BigDecimal limit,
                BigDecimal balance,
                BigDecimal cap,
                int terms,
                boolean hardBlock,
                String status,
                java.time.Instant openedAt) {}
        Optional<Row> found = jdbc
                .query(
                        """
                        select account_id, account_no, customer_id, credit_limit, balance, offline_cap, terms_days,
                               hard_block, status, opened_at
                          from customers.customer_account where account_id = ?
                        """,
                        (rs, n) -> new Row(
                                rs.getObject("account_id", UUID.class),
                                rs.getString("account_no"),
                                rs.getObject("customer_id", UUID.class),
                                rs.getBigDecimal("credit_limit"),
                                rs.getBigDecimal("balance"),
                                rs.getBigDecimal("offline_cap"),
                                rs.getInt("terms_days"),
                                rs.getBoolean("hard_block"),
                                rs.getString("status"),
                                rs.getTimestamp("opened_at").toInstant()),
                        accountId)
                .stream()
                .findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Row row = found.get();
        List<Allocator.OpenCharge> open = ledger.openCharges(accountId);
        BigDecimal available = row.limit().subtract(row.balance()).max(BigDecimal.ZERO);
        return Optional.of(new AccountView(
                row.accountId(),
                row.accountNo(),
                row.customerId(),
                row.limit(),
                row.balance(),
                available,
                row.cap(),
                row.terms(),
                row.hardBlock(),
                row.status(),
                row.openedAt(),
                open.isEmpty() ? null : open.get(0).businessDate(),
                ledger.unallocated(accountId),
                ageing(open, clock.today())));
    }

    /** 0–30, 31–60, 61–90 and over 90 days since the charge's business date, on what is open of it. */
    static AccountView.Ageing ageing(List<Allocator.OpenCharge> open, LocalDate today) {
        BigDecimal[] buckets = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Allocator.OpenCharge charge : open) {
            long days = ChronoUnit.DAYS.between(charge.businessDate(), today);
            int bucket = days <= 30 ? 0 : days <= 60 ? 1 : days <= 90 ? 2 : 3;
            buckets[bucket] = buckets[bucket].add(charge.open());
        }
        return new AccountView.Ageing(buckets[0], buckets[1], buckets[2], buckets[3]);
    }

    @Override
    public Optional<Payment> payment(UUID documentId, ScopeContext scope) {
        return jdbc
                .query(
                        """
                        select p.document_id, coalesce(p.doc_number_display, d.doc_number_display) as doc_number_display,
                               p.account_id, p.amount, p.origin, p.reversal_of,
                               (select r.document_id from customers.doc_customer_payment r
                                 where r.reversal_of = p.document_id) as reversed_by,
                               (select coalesce(sum(a.amount), 0) from customers.allocation a
                                  join customers.account_posting ap on ap.posting_id = a.payment_posting_id
                                 where ap.document_id = p.document_id and"""
                                + Ledger.LIVE_ALLOCATION
                                + """
                                ) as allocated
                          from customers.doc_customer_payment p
                          left join kernel.document d on d.document_id = p.document_id
                         where p.document_id = ?
                        """,
                        (rs, n) -> new Payment(
                                rs.getObject("document_id", UUID.class),
                                rs.getString("doc_number_display"),
                                rs.getObject("account_id", UUID.class),
                                rs.getBigDecimal("amount"),
                                rs.getBigDecimal("allocated"),
                                rs.getString("origin"),
                                rs.getObject("reversal_of", UUID.class),
                                rs.getObject("reversed_by", UUID.class)),
                        documentId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Statement> statement(UUID accountId, LocalDate from, LocalDate to, ScopeContext scope) {
        Integer visible = jdbc.queryForObject(
                "select count(*) from customers.customer_account where account_id = ?", Integer.class, accountId);
        if (visible == null || visible == 0) {
            return Optional.empty();
        }
        BigDecimal opening = jdbc.queryForObject(
                """
                select coalesce(sum(amount), 0) from customers.account_posting
                 where account_id = ? and business_date < ?
                """,
                BigDecimal.class,
                accountId,
                from);
        record Posting(
                UUID postingId,
                LocalDate businessDate,
                String kind,
                BigDecimal amount,
                BigDecimal settled,
                UUID documentId,
                String number,
                boolean breached,
                boolean offline,
                boolean reversed) {}
        List<Posting> postings = jdbc.query(
                """
                select p.posting_id, p.business_date, p.kind, p.amount, p.document_id, p.receipt_number,
                       p.limit_breached, p.offline,
                       case when"""
                        + Ledger.CHARGE_SIDE
                        + """
                            then (select coalesce(sum(a.amount), 0) from customers.allocation a
                                   where a.charge_posting_id = p.posting_id and"""
                        + Ledger.LIVE_ALLOCATION
                        + """
                                  )
                            when p.kind in ('PAYMENT', 'CREDIT') or (p.kind = 'ADJUSTMENT' and p.amount < 0)
                            then (select coalesce(sum(a.amount), 0) from customers.allocation a
                                   where a.payment_posting_id = p.posting_id and"""
                        + Ledger.LIVE_ALLOCATION
                        + """
                                  )
                            else 0 end as settled,
                       p.kind = 'PAYMENT' and exists (select 1 from customers.doc_customer_payment r
                                                       where r.reversal_of = p.document_id) as reversed
                  from customers.account_posting p
                 where p.account_id = ? and p.business_date between ? and ?
                 order by p.business_date, p.received_at, p.posting_id
                """,
                (rs, n) -> new Posting(
                        rs.getObject("posting_id", UUID.class),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getString("kind"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("settled"),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("receipt_number"),
                        rs.getBoolean("limit_breached"),
                        rs.getBoolean("offline"),
                        rs.getBoolean("reversed")),
                accountId,
                from,
                to);
        List<Line> lines = new ArrayList<>();
        BigDecimal running = opening;
        for (Posting posting : postings) {
            running = running.add(posting.amount());
            lines.add(new Line(
                    posting.postingId(),
                    posting.businessDate(),
                    posting.kind(),
                    posting.amount(),
                    posting.settled(),
                    running,
                    posting.documentId(),
                    posting.number(),
                    posting.breached(),
                    posting.offline(),
                    posting.reversed()));
        }
        return Optional.of(new Statement(accountId, from, to, opening, running, List.copyOf(lines)));
    }

    @Override
    public List<HistoryEntry> history(UUID accountId, ScopeContext scope) {
        return jdbc.query(
                """
                select history_id, action, before_value::text as before_value, after_value::text as after_value,
                       reason, changed_by, changed_at
                  from customers.account_history where account_id = ?
                 order by changed_at desc, history_id desc
                """,
                (rs, n) -> new HistoryEntry(
                        rs.getObject("history_id", UUID.class),
                        rs.getString("action"),
                        map(rs.getString("before_value")),
                        map(rs.getString("after_value")),
                        rs.getString("reason"),
                        rs.getObject("changed_by", UUID.class),
                        rs.getTimestamp("changed_at").toInstant()),
                accountId);
    }

    @Override
    public List<Adjustment> adjustments(UUID accountId, ScopeContext scope) {
        return jdbc.query(
                """
                select adjustment_id, amount, reason, status, requested_by, requested_at, approved_by, approved_at
                  from customers.account_adjustment where account_id = ?
                 order by requested_at desc, adjustment_id desc
                """,
                (rs, n) -> new Adjustment(
                        rs.getObject("adjustment_id", UUID.class),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason"),
                        rs.getString("status"),
                        rs.getObject("requested_by", UUID.class),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getObject("approved_by", UUID.class),
                        rs.getTimestamp("approved_at") == null
                                ? null
                                : rs.getTimestamp("approved_at").toInstant()),
                accountId);
    }

    private Map<String, Object> map(String json) {
        try {
            return this.json.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of();
        }
    }
}
