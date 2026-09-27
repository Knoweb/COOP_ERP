package lk.coopfed.knoweb.m4trading.internal.queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
import lk.coopfed.knoweb.m4trading.query.GrnView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The GRNs, from the document base and M4's extension rows; unpaged for the demo. */
@Service
class GrnQueriesImpl implements GrnQueries {

    private final JdbcTemplate jdbc;
    private final GrnReads reads;

    GrnQueriesImpl(JdbcTemplate jdbc, GrnReads reads) {
        this.jdbc = jdbc;
        this.reads = reads;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GrnView> getGrn(UUID grnId, ScopeContext scope) {
        if (grnId == null) {
            return Optional.empty();
        }
        return reads.grn(grnId)
                .map(grn -> new GrnView(
                        grn.header().id(),
                        grn.header().docNumberDisplay(),
                        grn.header().status(),
                        grn.receiverEntityId(),
                        grn.receiverLocationId(),
                        grn.sellerEntityId(),
                        grn.dropId(),
                        grn.deliveryDocumentId(),
                        grn.receivedOn(),
                        grn.confirmedAt(),
                        jdbc
                                .queryForList(
                                        "select document_id from trading.doc_discrepancy where grn_document_id = ?",
                                        UUID.class,
                                        grnId)
                                .stream()
                                .findFirst()
                                .orElse(null),
                        grn.lines().stream()
                                .map(line -> new GrnView.GrnLineView(
                                        line.lineId(),
                                        line.lineNo(),
                                        line.skuId(),
                                        line.uomCode(),
                                        line.expectedQty(),
                                        line.receivedQty(),
                                        line.damagedQty(),
                                        line.batchNo(),
                                        line.expiryDate(),
                                        line.printedMrp(),
                                        line.unitCost(),
                                        line.batchId()))
                                .toList()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<GrnView> listGrns(OrderQueries.Role role, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return List.of();
        }
        String column = role == OrderQueries.Role.SELLER ? "seller_entity_id" : "receiver_entity_id";
        List<GrnView> views = new ArrayList<>();
        for (UUID id : jdbc.queryForList(
                "select document_id from trading.doc_grn where " + column + " = ? order by created_at desc",
                UUID.class,
                scope.entityId())) {
            getGrn(id, scope)
                    .filter(view -> role != OrderQueries.Role.SELLER || view.confirmedAt() != null)
                    .ifPresent(views::add);
        }
        return views;
    }
}
