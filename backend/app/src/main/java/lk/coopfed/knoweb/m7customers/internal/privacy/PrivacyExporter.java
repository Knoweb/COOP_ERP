package lk.coopfed.knoweb.m7customers.internal.privacy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The access export (27A section 6: "ACCESS: export"; section 9: "access export contains every
 * field the customer's rows hold"): every row the caller's society holds about the customer, as
 * the columns are named, read under the caller's policies. The customer's identity (without the
 * NIC's hash and key id, which are the system's secret, not the customer's data), phone history,
 * consents and tags; the society's account, its postings, the allocations and the repayment
 * receipts. Values are text (a date as ISO text, an amount as its decimal text), so the export's
 * hash is stable. It only reads; {@code DownloadAccessExportHandler} audits the hand-over.
 */
@Component
public class PrivacyExporter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    PrivacyExporter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * The customer's row by column name: never {@code nic_hash} or {@code nic_key_id} (a derived
     * secret, M7CR-11 and RLS-01); {@code nic_last4} stays, it is the customer's own.
     */
    static final String CUSTOMER_COLUMNS = "customer_id, display_name, display_name_si, display_name_ta, language,"
            + " nic_last4, attributes, status, registered_by_entity_id, registered_at, owner_entity_id";

    public Map<String, Object> export(UUID customerId) {
        Map<String, Object> export = new LinkedHashMap<>();
        export.put(
                "customer",
                rows("select " + CUSTOMER_COLUMNS + " from customers.customer where customer_id = ?", customerId));
        export.put(
                "phones",
                rows("select * from customers.customer_phone where customer_id = ? order by valid_from", customerId));
        export.put(
                "consents",
                rows("select * from customers.customer_consent where customer_id = ? order by granted_at", customerId));
        export.put(
                "tags",
                rows("select * from customers.customer_tag where customer_id = ? order by tag_code", customerId));
        export.put("accounts", rows("select * from customers.customer_account where customer_id = ?", customerId));
        export.put(
                "postings",
                rows(
                        """
                        select p.* from customers.account_posting p
                          join customers.customer_account a on a.account_id = p.account_id
                         where a.customer_id = ? order by p.business_date, p.received_at
                        """,
                        customerId));
        export.put(
                "allocations",
                rows(
                        """
                        select al.* from customers.allocation al
                          join customers.account_posting p on p.posting_id = al.payment_posting_id
                          join customers.customer_account a on a.account_id = p.account_id
                         where a.customer_id = ? order by al.created_at
                        """,
                        customerId));
        export.put(
                "payments",
                rows(
                        """
                        select d.* from customers.doc_customer_payment d
                          join customers.customer_account a on a.account_id = d.account_id
                         where a.customer_id = ? order by d.received_at
                        """,
                        customerId));
        return export;
    }

    /** The export as the bytes handed over; the same data always gives the same bytes. */
    public byte[] bytes(Map<String, Object> export) {
        try {
            return json.writeValueAsBytes(export);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String sha256(Map<String, Object> export) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(export)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every row of the query as column name to text. */
    private List<Map<String, Object>> rows(String sql, UUID customerId) {
        return jdbc.queryForList(sql, customerId).stream()
                .map(row -> {
                    Map<String, Object> text = new LinkedHashMap<>();
                    row.forEach((column, value) -> text.put(column, text(value)));
                    return text;
                })
                .toList();
    }

    private static Object text(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Array array) {
            try {
                return List.of((Object[]) array.getArray()).stream()
                        .map(String::valueOf)
                        .toList();
            } catch (java.sql.SQLException e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }
}
