package lk.coopfed.knoweb.m5inventory.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

public interface StockTakeQueries {

    Optional<StockTakeSessionView> activeSession(UUID locationId, ScopeContext scope);

    Optional<StockTakeSessionView> getSession(UUID sessionId, ScopeContext scope);

    StockTakeReviewView getReview(UUID sessionId, ScopeContext scope);

    List<StockTakeScanView> listScans(UUID sessionId, ScopeContext scope);
}
