package lk.coopfed.knoweb.m7customers.internal.queries;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.internal.customer.PhoneNumbers;
import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.m7customers.query.AccountView;
import lk.coopfed.knoweb.m7customers.query.CustomerCard;
import lk.coopfed.knoweb.m7customers.query.CustomerQueries;
import lk.coopfed.knoweb.m7customers.query.CustomerSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer register and card, read under the caller's scope: row-level security returns the
 * customers the society registered and those it holds an account for (27A section 3). The account
 * joined is the caller's society's own; another society's balance is never read here.
 */
@Service
@Transactional(readOnly = true)
class CustomerQueriesImpl implements CustomerQueries {

    static final int LIMIT_MAX = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AccountQueries accounts;

    CustomerQueriesImpl(JdbcTemplate jdbc, ObjectMapper json, AccountQueries accounts) {
        this.jdbc = jdbc;
        this.json = json;
        this.accounts = accounts;
    }

    @Override
    public List<CustomerSummary> search(String name, String phone, int limit, ScopeContext scope) {
        String q = name == null || name.isBlank() ? null : "%" + escape(name.strip()) + "%";
        String e164 = phone == null || phone.isBlank()
                ? null
                : PhoneNumbers.normalise(phone).orElse("-");
        return jdbc.query(
                """
                select c.customer_id, c.display_name, c.display_name_si, c.display_name_ta, c.language, c.status,
                       (select ph.phone from customers.customer_phone ph
                         where ph.customer_id = c.customer_id and ph.is_primary and ph.valid_to is null
                         limit 1) as phone,
                       a.account_id, a.account_no, a.credit_limit, a.balance
                  from customers.customer c
                  left join customers.customer_account a
                         on a.customer_id = c.customer_id and a.owner_entity_id = ?
                 where (cast(? as text) is null
                        or c.display_name ilike ? or c.display_name_si ilike ? or c.display_name_ta ilike ?)
                   and (cast(? as text) is null
                        or exists (select 1 from customers.customer_phone ph
                                    where ph.customer_id = c.customer_id and ph.phone = ?
                                      and ph.is_primary and ph.valid_to is null))
                 order by c.display_name, c.customer_id
                 limit ?
                """,
                (rs, n) -> new CustomerSummary(
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("display_name_si"),
                        rs.getString("display_name_ta"),
                        rs.getString("language"),
                        rs.getString("phone"),
                        rs.getString("status"),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_no"),
                        rs.getBigDecimal("credit_limit"),
                        rs.getBigDecimal("balance")),
                scope.entityId(),
                q,
                q,
                q,
                q,
                e164,
                e164,
                Math.max(1, Math.min(limit, LIMIT_MAX)));
    }

    @Override
    public Optional<CustomerCard> card(UUID customerId, ScopeContext scope) {
        record Identity(
                String name,
                String si,
                String ta,
                String language,
                String nicLast4,
                String status,
                Instant registeredAt,
                UUID registeredBy,
                Map<String, String> attributes) {}
        Optional<Identity> identity = jdbc
                .query(
                        """
                        select display_name, display_name_si, display_name_ta, language, nic_last4, status,
                               registered_at, registered_by_entity_id, attributes::text as attributes
                          from customers.customer where customer_id = ?
                        """,
                        (rs, n) -> new Identity(
                                rs.getString("display_name"),
                                rs.getString("display_name_si"),
                                rs.getString("display_name_ta"),
                                rs.getString("language"),
                                rs.getString("nic_last4"),
                                rs.getString("status"),
                                rs.getTimestamp("registered_at").toInstant(),
                                rs.getObject("registered_by_entity_id", UUID.class),
                                attributes(rs.getString("attributes"))),
                        customerId)
                .stream()
                .findFirst();
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        Identity row = identity.get();
        List<CustomerCard.Phone> phones = jdbc.query(
                """
                select phone, valid_from, valid_to, reason from customers.customer_phone
                 where customer_id = ? and is_primary order by valid_from desc
                """,
                (rs, n) -> new CustomerCard.Phone(
                        rs.getString("phone"),
                        instant(rs, "valid_from"),
                        instant(rs, "valid_to"),
                        rs.getString("reason")),
                customerId);
        List<CustomerCard.Consent> consents = jdbc.query(
                """
                select purpose, granted_via, granted_at, withdrawn_at from customers.customer_consent
                 where customer_id = ? order by granted_at, purpose
                """,
                (rs, n) -> new CustomerCard.Consent(
                        rs.getString("purpose"),
                        rs.getString("granted_via"),
                        instant(rs, "granted_at"),
                        instant(rs, "withdrawn_at")),
                customerId);
        List<String> tags = jdbc.queryForList(
                """
                select tag_code from customers.customer_tag
                 where customer_id = ? and owner_entity_id = ? and removed_at is null order by tag_code
                """,
                String.class,
                customerId,
                scope.entityId());
        List<UUID> accountIds = jdbc.queryForList(
                "select account_id from customers.customer_account where customer_id = ? and owner_entity_id = ?",
                UUID.class,
                customerId,
                scope.entityId());
        AccountView account = accountIds.isEmpty()
                ? null
                : accounts.account(accountIds.get(0), scope).orElse(null);
        String current = phones.stream()
                .filter(p -> p.validTo() == null)
                .map(CustomerCard.Phone::phone)
                .findFirst()
                .orElse(null);
        return Optional.of(new CustomerCard(
                customerId,
                row.name(),
                row.si(),
                row.ta(),
                row.language(),
                current,
                row.nicLast4(),
                row.status(),
                row.registeredAt(),
                row.registeredBy().equals(scope.entityId()),
                row.attributes(),
                tags,
                consents,
                phones,
                account));
    }

    private Map<String, String> attributes(String text) {
        try {
            return text == null ? Map.of() : json.readValue(text, new TypeReference<Map<String, String>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of();
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** A name search is a substring: the user's % and _ are letters, not wildcards. */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
