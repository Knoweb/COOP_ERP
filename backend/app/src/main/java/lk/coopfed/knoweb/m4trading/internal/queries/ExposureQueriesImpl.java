package lk.coopfed.knoweb.m4trading.internal.queries;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipSide;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.query.ExposureQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The exposures of the caller's relationships (doc 24 section 5.2, GetExposure / ListExposures):
 * the ACTIVE row of each pair in force today (M1), each computed by {@link ExposureCalculator}. A
 * pair is one seller and one buyer: an amendment of the terms opens a new relationship row, and
 * the exposure carries over to it.
 */
@Service
class ExposureQueriesImpl implements ExposureQueries {

    private final RelationshipQueries relationships;
    private final ExposureCalculator calculator;
    private final TradingClock clock;

    ExposureQueriesImpl(RelationshipQueries relationships, ExposureCalculator calculator, TradingClock clock) {
        this.relationships = relationships;
        this.calculator = calculator;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExposureView> listExposures(OrderQueries.Role role, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return List.of();
        }
        RelationshipSide side = role == OrderQueries.Role.BUYER ? RelationshipSide.BUYER : RelationshipSide.SELLER;
        return relationships.listRelationships(side, scope).stream()
                .filter(this::inForce)
                .map(row -> calculator.exposure(row, scope))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExposureView> exposure(UUID sellerEntityId, UUID buyerEntityId, ScopeContext scope) {
        if (sellerEntityId == null || buyerEntityId == null || scope == null) {
            return Optional.empty();
        }
        return relationships
                .lookupRelationship(sellerEntityId, buyerEntityId, clock.today(), scope)
                .filter(this::inForce)
                .map(row -> calculator.exposure(row, scope));
    }

    private boolean inForce(RelationshipView row) {
        return "ACTIVE".equals(row.status())
                && !clock.today().isBefore(row.effectiveFrom())
                && (row.effectiveTo() == null || !clock.today().isAfter(row.effectiveTo()));
    }
}
