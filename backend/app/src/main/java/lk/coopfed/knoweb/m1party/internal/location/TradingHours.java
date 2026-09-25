package lk.coopfed.knoweb.m1party.internal.location;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m1party.api.TradingDay;

/**
 * The trading hours of a location, between the list the API carries and the JSON array the
 * column holds. The shape of one day (a day name, two HH:mm times) is the slice's to check; the
 * rule that a day appears once and closes after it opens is a business rule, checked here so
 * that it holds for a command that does not arrive over HTTP (a bulk file, a sync) as well.
 */
public final class TradingHours {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<TradingDay>> LIST = new TypeReference<>() {};

    private TradingHours() {}

    /** The JSON to store, or null for no hours; refuses {@code m1.location.trading_hours_invalid}. */
    static String toJson(List<TradingDay> days) {
        if (days == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        for (TradingDay day : days) {
            if (day == null
                    || day.day() == null
                    || day.opens() == null
                    || day.closes() == null
                    || !seen.add(day.day())
                    || !closesAfterOpens(day)) {
                throw invalid(day);
            }
        }
        try {
            return JSON.writeValueAsString(days);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Trading hours could not be written as JSON", e);
        }
    }

    /** The stored JSON as the list the API carries; an empty list for none. */
    public static List<TradingDay> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(JSON.readValue(json, LIST));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored trading hours are not a list of days: " + json, e);
        }
    }

    private static boolean closesAfterOpens(TradingDay day) {
        try {
            return LocalTime.parse(day.closes()).isAfter(LocalTime.parse(day.opens()));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static ProblemException invalid(TradingDay day) {
        return new ProblemException(
                "m1.location.trading_hours_invalid", Map.of("day", day == null ? "" : String.valueOf(day.day())));
    }
}
