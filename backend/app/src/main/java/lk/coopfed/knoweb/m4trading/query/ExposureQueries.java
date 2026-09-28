package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** GetExposure and ListExposures (doc 24 section 5.2): computed on read from the trading records. */
public interface ExposureQueries {

    /** One exposure per ACTIVE relationship in which the caller's entity sells (SELLER) or buys (BUYER). */
    List<ExposureView> listExposures(OrderQueries.Role role, ScopeContext scope);

    /** The exposure of one pair the caller is part of; empty when there is no ACTIVE relationship. */
    Optional<ExposureView> exposure(UUID sellerEntityId, UUID buyerEntityId, ScopeContext scope);
}
