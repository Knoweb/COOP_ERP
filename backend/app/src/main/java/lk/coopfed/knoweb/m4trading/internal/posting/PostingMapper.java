package lk.coopfed.knoweb.m4trading.internal.posting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.m4trading.api.Posting;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostingMapper (24A section 4, posting/; doc 24 section 3.9): a document's amounts to journal
 * lines by account role, through the seeded {@code trading.posting_map}. One posting per map row
 * of the document type, line kind and side whose amount source the document has; a zero amount
 * gives no line. Account roles only: the chart of accounts is mapped when the accounting system is
 * chosen (J-02).
 */
@Component
public class PostingMapper {

    private final JdbcTemplate jdbc;

    PostingMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param amounts the document's amounts by source ({@code net}, {@code tax}, {@code cost}, ...)
     */
    public List<Posting> postings(String docTypeCode, String lineKind, String side, Map<String, BigDecimal> amounts) {
        List<Posting> postings = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                """
                select debit_role, credit_role, amount_source from trading.posting_map
                 where doc_type_code = ? and line_kind = ? and side = ?
                 order by debit_role, credit_role
                """,
                docTypeCode,
                lineKind,
                side)) {
            BigDecimal amount = amounts.get((String) row.get("amount_source"));
            if (amount == null || amount.signum() == 0) {
                continue;
            }
            postings.add(new Posting(
                    lineKind,
                    side,
                    (String) row.get("debit_role"),
                    (String) row.get("credit_role"),
                    (String) row.get("amount_source"),
                    amount));
        }
        return postings;
    }
}
