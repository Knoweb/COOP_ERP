package lk.coopfed.knoweb.m2catalogue.internal.queries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BatchFilter;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m2catalogue.query.SupplierView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The batch and supplier reads, under the caller's scope (row-level security decides). */
@Service
@Transactional(readOnly = true)
class BatchQueriesImpl implements BatchQueries {

    private static final String SELECT =
            """
            select batch_id, sku_id, supplier_id, batch_no, manufacture_date, expiry_date, printed_mrp,
                   origin_document_id, is_synthetic, corrects_batch_id, status, owner_entity_id, created_at
            from catalogue.batch
            """;

    private final JdbcTemplate jdbc;

    BatchQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BatchView> getBatch(UUID batchId, ScopeContext scope) {
        if (batchId == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " where batch_id = ?", BatchQueriesImpl::map, batchId).stream()
                .findFirst();
    }

    @Override
    public List<BatchView> listBatches(BatchFilter filter, ScopeContext scope) {
        if (filter == null || filter.skuId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "skuId"));
        }
        if (scope == null || !scope.hasActiveScope()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder(SELECT).append(" where sku_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(filter.skuId());

        if (filter.batchNo() != null && !filter.batchNo().isBlank()) {
            sql.append(" and batch_no = ?");
            args.add(filter.batchNo().strip());
        }
        LocalDate expiringBefore = filter.expiringBefore();
        if (expiringBefore != null) {
            sql.append(" and expiry_date < ?");
            args.add(expiringBefore);
        }
        sql.append(" order by created_at desc, batch_id desc limit ?");
        args.add(filter.normalizedLimit());

        return jdbc.query(sql.toString(), BatchQueriesImpl::map, args.toArray());
    }

    @Override
    public List<SupplierView> listSuppliers(ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope() || scope.entityId() == null) {
            return List.of();
        }
        return jdbc.query(
                "select supplier_id, owner_entity_id, name, status from catalogue.supplier"
                        + " where owner_entity_id = ? order by name",
                (rs, row) -> new SupplierView(
                        rs.getObject("supplier_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status")),
                scope.entityId());
    }

    private static BatchView map(ResultSet rs, int row) throws SQLException {
        return new BatchView(
                rs.getObject("batch_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("batch_no"),
                rs.getObject("manufacture_date", LocalDate.class),
                rs.getObject("expiry_date", LocalDate.class),
                rs.getBigDecimal("printed_mrp"),
                rs.getObject("origin_document_id", UUID.class),
                rs.getBoolean("is_synthetic"),
                rs.getObject("corrects_batch_id", UUID.class),
                rs.getString("status"),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
