package lk.coopfed.knoweb.m1party.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Replaces the descriptive facts of a location (doc 21 section 7: trading hours, language,
 * size band). The code and the type are not here: the code is part of every document number of
 * the location, and the type decides which numbering series it has.
 *
 * @param language the till and receipt language; null takes the entity's default language
 */
public record UpdateLocation(
        UUID locationId,
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
