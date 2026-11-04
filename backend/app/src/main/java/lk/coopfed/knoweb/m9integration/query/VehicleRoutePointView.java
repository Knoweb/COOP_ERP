package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Historical GPS track point on a vehicle's route (M3-06).
 */
public record VehicleRoutePointView(
        Instant recordedAt, BigDecimal latitude, BigDecimal longitude, BigDecimal speedKmh, Integer headingDegrees) {}
