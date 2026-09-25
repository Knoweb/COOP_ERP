package lk.coopfed.knoweb.m1party.api;

/**
 * The hours of one day of the week at a location (doc 21 section 3.3: trading hours drive the
 * update windows of doc 31 and the day-open of doc 32). A day that is not listed is closed.
 *
 * @param day    MON, TUE, WED, THU, FRI, SAT or SUN
 * @param opens  local time, HH:mm
 * @param closes local time, HH:mm, after {@code opens}
 */
public record TradingDay(String day, String opens, String closes) {}
