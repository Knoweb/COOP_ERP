package lk.coopfed.knoweb.m6pos.internal.query;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m6pos.query.PosQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PosQueries under row-level security: no query names an owner. */
@Service
@Transactional(readOnly = true)
class PosQueriesImpl implements PosQueries {

    private final JdbcTemplate jdbc;

    PosQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReceiptView> receipts(UUID locationId, ScopeContext scope) {
        return jdbc.query(
                """
                select document_id, location_id, device_id, session_id, doc_number_display, issued_at, business_date,
                       gross_amount, flags
                  from pos.receipt
                 where location_id = ?
                 order by issued_at desc, document_id
                """,
                (rs, n) -> {
                    UUID id = rs.getObject("document_id", UUID.class);
                    return new ReceiptView(
                            id,
                            rs.getObject("location_id", UUID.class),
                            rs.getObject("device_id", UUID.class),
                            rs.getObject("session_id", UUID.class),
                            rs.getString("doc_number_display"),
                            rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("business_date", LocalDate.class),
                            rs.getBigDecimal("gross_amount"),
                            texts(rs.getArray("flags")),
                            lines(id));
                },
                locationId);
    }

    private List<ReceiptView.Line> lines(UUID documentId) {
        return jdbc.query(
                """
                select line_no, sku_id, batch_id, qty, unit_price, line_total
                  from pos.receipt_line
                 where document_id = ?
                 order by line_no
                """,
                (rs, n) -> new ReceiptView.Line(
                        rs.getInt("line_no"),
                        rs.getObject("sku_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("line_total")),
                documentId);
    }

    @Override
    public List<SessionView> sessions(UUID locationId, ScopeContext scope) {
        return jdbc.query(
                """
                select s.session_id, s.location_id, s.till_position_id, s.business_date, s.opened_at, s.float_amount,
                       c.closed_at, c.counted_cash, c.expected_cash, c.variance
                  from pos.till_session s
                  left join pos.till_session_close c on c.session_id = s.session_id
                 where s.location_id = ?
                 order by s.opened_at desc, s.session_id
                """,
                (rs, n) -> new SessionView(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("till_position_id", UUID.class),
                        rs.getObject("business_date", LocalDate.class),
                        instant(rs, "opened_at"),
                        rs.getBigDecimal("float_amount"),
                        instant(rs, "closed_at"),
                        rs.getBigDecimal("counted_cash"),
                        rs.getBigDecimal("expected_cash"),
                        rs.getBigDecimal("variance")),
                locationId);
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
