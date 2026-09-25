package lk.coopfed.knoweb.m1party.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 21A section 6, RegisterLocation. The owner is the caller's entity and never a field: a
 * location is registered by the entity it belongs to (doc 21 section 4.3).
 *
 * @param language the till and receipt language; null takes the entity's default language
 */
public record RegisterLocation(
        String locationCode,
        String locationType,
        String nameEn,
        String nameSi,
        String nameTa,
        String address,
        String district,
        BigDecimal geoLat,
        BigDecimal geoLng,
        String language,
        List<TradingDay> tradingHours,
        String sizeBand) {}
