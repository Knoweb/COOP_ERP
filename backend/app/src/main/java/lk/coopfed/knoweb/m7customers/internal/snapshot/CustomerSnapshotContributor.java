package lk.coopfed.knoweb.m7customers.internal.snapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The society's credit customers in its tills' snapshot (27A section 7.3,
 * CustomerSnapshotContributor): what the till needs to find a customer by phone and take an
 * ACCOUNT tender or a repayment on its own rules (27A section 7.3, "Till contract"), and nothing
 * more ("Read this first": name, phone, balance, limit).
 *
 * <p>Snapshot table {@code customer}, row id = customer id. A row is an ACTIVE customer holding an
 * account OPEN or SUSPENDED at the shop's society (a SUSPENDED one travels so the till can say why
 * it refuses); an INACTIVE or anonymised customer (wave 2, M7CR-05), a CLOSED account and a
 * customer of another society are not in it, and the kernel sends the till a tombstone for a row
 * that leaves. The row: the three names (the till shows the shop's language and falls back to
 * English), the language, the current primary phone, the account, its limit, balance and offline
 * cap as decimal text, the hard block, the status and the society's tags. Never the NIC's hash or
 * last four, the attributes or the consents (27A section 7.3: "never: nic_hash, nic_last4,
 * attributes, consent details").
 *
 * <p>The change log (27A: "customer.*, account.* ... -> UPSERT; account.suspended -> urgent") is
 * fed by {@link CustomerChangeLogFanOut} from the module's own events (wave 2, M7CR-14).
 */
@Component
class CustomerSnapshotContributor implements SnapshotContributor {

    static final String CUSTOMER = "customer";

    private final NamedParameterJdbcTemplate jdbc;

    CustomerSnapshotContributor(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Set<String> tables() {
        return Set.of(CUSTOMER);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (rowIds.isEmpty()) {
            return Map.of();
        }
        return customers(shop, rowIds);
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        return customers(shop, null);
    }

    /** The society's credit customers; {@code customerIds} null for all of them. */
    private Map<UUID, Map<String, Object>> customers(Shop shop, Collection<UUID> customerIds) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("entity", shop.ownerEntityId())
                .addValue("ids", customerIds);
        String onlyIds = customerIds == null ? "" : " and c.customer_id in (:ids)";
        Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbc.query(
                """
                select c.customer_id, c.display_name, c.display_name_si, c.display_name_ta, c.language,
                       (select ph.phone from customers.customer_phone ph
                         where ph.customer_id = c.customer_id and ph.is_primary and ph.valid_to is null) as phone,
                       a.account_id, a.account_no, a.credit_limit, a.balance, a.offline_cap, a.hard_block, a.status,
                       array(select t.tag_code from customers.customer_tag t
                              where t.customer_id = c.customer_id and t.owner_entity_id = a.owner_entity_id
                                and t.removed_at is null
                              order by t.tag_code) as tags
                  from customers.customer_account a
                  join customers.customer c on c.customer_id = a.customer_id
                 where a.owner_entity_id = :entity
                   and a.status in ('OPEN', 'SUSPENDED')
                   and c.status = 'ACTIVE'
                """
                        + onlyIds
                        + " order by c.customer_id",
                params,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("display_name", rs.getString("display_name"));
                    row.put("display_name_si", rs.getString("display_name_si"));
                    row.put("display_name_ta", rs.getString("display_name_ta"));
                    row.put("language", rs.getString("language"));
                    row.put("phone", rs.getString("phone"));
                    row.put("account_id", rs.getObject("account_id", UUID.class).toString());
                    row.put("account_no", rs.getString("account_no"));
                    row.put("credit_limit", text(rs.getBigDecimal("credit_limit")));
                    row.put("balance", text(rs.getBigDecimal("balance")));
                    row.put("offline_cap", text(rs.getBigDecimal("offline_cap")));
                    row.put("hard_block", rs.getBoolean("hard_block"));
                    row.put("status", rs.getString("status"));
                    List<String> tags = new ArrayList<>();
                    java.sql.Array array = rs.getArray("tags");
                    if (array != null) {
                        for (Object tag : (Object[]) array.getArray()) {
                            tags.add(String.valueOf(tag));
                        }
                    }
                    row.put("tags", tags);
                    rows.put(rs.getObject("customer_id", UUID.class), row);
                });
        return rows;
    }

    /** A decimal as text, never rounded by the till (SnapshotContributor). */
    private static String text(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2).toPlainString();
    }
}
