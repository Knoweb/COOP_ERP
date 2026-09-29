package lk.coopfed.knoweb.m4trading.internal.queries;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.internal.transfer.TransferRequestReads;
import lk.coopfed.knoweb.m4trading.query.TransferRequestQueries;
import lk.coopfed.knoweb.m4trading.query.TransferRequestView;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.TransferView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transfer requests, from {@code trading.transfer_request} and the society's decision row
 * (V0008), and the transfer that fulfils an approved one from M5 ({@code
 * InventoryQueries.transferOfRequest}): M5's issue row names the request, so M4 writes nothing
 * back (module README, "Transfer requests").
 */
@Service
class TransferRequestQueriesImpl implements TransferRequestQueries {

    private final TransferRequestReads requests;
    private final InventoryQueries inventory;

    TransferRequestQueriesImpl(TransferRequestReads requests, InventoryQueries inventory) {
        this.requests = requests;
        this.inventory = inventory;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TransferRequestView> getRequest(UUID requestId, ScopeContext scope) {
        return requests.request(requestId).map(request -> view(request, scope));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferRequestView> listRequests(ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return List.of();
        }
        return requests.requestIds().stream()
                .map(requests::request)
                .flatMap(Optional::stream)
                .map(request -> view(request, scope))
                .toList();
    }

    private TransferRequestView view(TransferRequestReads.Request request, ScopeContext scope) {
        Optional<TransferView> transfer = TransferRequestReads.APPROVED.equals(request.status())
                ? inventory.transferOfRequest(request.requestId(), scope)
                : Optional.empty();
        return new TransferRequestView(
                request.requestId(),
                request.ownerEntityId(),
                request.fromLocationId(),
                request.toLocationId(),
                request.status(),
                request.reason(),
                request.requestedBy(),
                request.requestedAt(),
                request.decisionReason(),
                request.decidedBy(),
                request.decidedAt(),
                transfer.map(TransferView::transferId).orElse(null),
                transfer.map(TransferView::status).orElse(null),
                request.lines().stream()
                        .map(line -> new TransferRequestView.Line(line.lineId(), line.skuId(), line.qty()))
                        .toList());
    }
}
