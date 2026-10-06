package lk.coopfed.knoweb.m7customers.internal.queries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.query.PrivacyQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The privacy requests, read under the caller's scope (row-level security filters). The access
 * export is no read: it is handed over by {@code DownloadAccessExport}, a command, so that the
 * hand-over is the officer's and audited (wave 2, M7CR-11).
 */
@Service
@Transactional(readOnly = true)
class PrivacyQueriesImpl implements PrivacyQueries {

    private static final String SELECT =
            """
            select r.request_id, r.customer_id, c.display_name, r.kind, r.notes, r.received_at, r.received_by, r.status,
                   r.fulfilled_at, r.fulfilled_by, r.outcome, r.export_sha256, r.refusal_ground
              from customers.data_subject_request r
              left join customers.customer c on c.customer_id = r.customer_id
            """;

    private final JdbcTemplate jdbc;

    PrivacyQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RequestView> requests(String status, ScopeContext scope) {
        if (status == null) {
            return jdbc.query(SELECT + " order by r.received_at desc, r.request_id desc", PrivacyQueriesImpl::row);
        }
        return jdbc.query(
                SELECT + " where r.status = ? order by r.received_at desc, r.request_id desc",
                PrivacyQueriesImpl::row,
                status);
    }

    @Override
    public Optional<RequestView> request(UUID requestId, ScopeContext scope) {
        return jdbc.query(SELECT + " where r.request_id = ?", PrivacyQueriesImpl::row, requestId).stream()
                .findFirst();
    }

    private static RequestView row(ResultSet rs, int n) throws SQLException {
        return new RequestView(
                rs.getObject("request_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("kind"),
                rs.getString("notes"),
                instant(rs.getTimestamp("received_at")),
                rs.getObject("received_by", UUID.class),
                rs.getString("status"),
                instant(rs.getTimestamp("fulfilled_at")),
                rs.getObject("fulfilled_by", UUID.class),
                rs.getString("outcome"),
                rs.getString("export_sha256"),
                rs.getString("refusal_ground"));
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
