package lk.coopfed.knoweb.m4trading.internal.queries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.query.OpeningReceivableQueries;
import lk.coopfed.knoweb.m4trading.query.OpeningReceivableView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of OpeningReceivableQueries reading through document-level RLS policies. */
@Service
class OpeningReceivableQueriesImpl implements OpeningReceivableQueries {

    private final JdbcTemplate jdbc;

    OpeningReceivableQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OpeningReceivableView> getOpeningReceivable(UUID documentId, ScopeContext scope) {
        if (documentId == null) {
            return Optional.empty();
        }

        List<OpeningReceivableView> list = jdbc.query(
                """
                select r.opening_receivable_id, r.document_id, d.doc_number_display, r.relationship_id,
                       r.seller_entity_id, r.buyer_entity_id, r.amount, r.reference_no,
                       r.seller_signed_by, r.seller_signed_at, r.buyer_signed_by, r.buyer_signed_at,
                       r.notes, r.created_at
                  from trading.opening_receivable r
                  join kernel.document d on d.document_id = r.document_id
                 where r.document_id = ?
                """,
                (rs, rowNum) -> map(rs),
                documentId);

        return list.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpeningReceivableView> listOpeningReceivables(UUID counterpartyId, ScopeContext scope) {
        if (counterpartyId != null) {
            return jdbc.query(
                    """
                    select r.opening_receivable_id, r.document_id, d.doc_number_display, r.relationship_id,
                           r.seller_entity_id, r.buyer_entity_id, r.amount, r.reference_no,
                           r.seller_signed_by, r.seller_signed_at, r.buyer_signed_by, r.buyer_signed_at,
                           r.notes, r.created_at
                      from trading.opening_receivable r
                      join kernel.document d on d.document_id = r.document_id
                     where (r.seller_entity_id = ? or r.buyer_entity_id = ?)
                     order by r.created_at desc
                    """,
                    (rs, rowNum) -> map(rs),
                    counterpartyId,
                    counterpartyId);
        }

        return jdbc.query(
                """
                select r.opening_receivable_id, r.document_id, d.doc_number_display, r.relationship_id,
                       r.seller_entity_id, r.buyer_entity_id, r.amount, r.reference_no,
                       r.seller_signed_by, r.seller_signed_at, r.buyer_signed_by, r.buyer_signed_at,
                       r.notes, r.created_at
                  from trading.opening_receivable r
                  join kernel.document d on d.document_id = r.document_id
                 order by r.created_at desc
                """,
                (rs, rowNum) -> map(rs));
    }

    private static OpeningReceivableView map(ResultSet rs) throws SQLException {
        return new OpeningReceivableView(
                rs.getObject("opening_receivable_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("doc_number_display"),
                rs.getObject("relationship_id", UUID.class),
                rs.getObject("seller_entity_id", UUID.class),
                rs.getObject("buyer_entity_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("reference_no"),
                rs.getObject("seller_signed_by", UUID.class),
                toInstant(rs.getTimestamp("seller_signed_at")),
                rs.getObject("buyer_signed_by", UUID.class),
                toInstant(rs.getTimestamp("buyer_signed_at")),
                rs.getString("notes"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
