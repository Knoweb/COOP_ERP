package lk.coopfed.knoweb.m1party.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m1party.api.TradingDay;

/** A location as GetLocation and ListLocations answer it (doc 21 section 5.2: with primary till, hours, language). */
public record LocationView(
        UUID locationId,
        UUID ownerEntityId,
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
        String sizeBand,
        boolean connectivitySpecMet,
        UUID primaryTillPositionId,
        String status) {}
