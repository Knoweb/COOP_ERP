package lk.coopfed.knoweb.kernel.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * The one way numbers, money and dates are written for a person (doc 19 section 5.1; DR-6):
 * Western Arabic digits in every language, Sinhala and Tamil included, because a receipt, a
 * tax document and the screen must show the same figures; dates {@code dd/MM/yyyy}; money
 * {@code Rs 1,234.00} with two decimals. ICU would give si-LK and ta-LK native numerals for
 * some formats; the platform overrides that once, here, with the LATN numbering system.
 */
public interface Formats {

    /** {@code 1,234.50}: grouped, two decimals, LATN digits whatever the locale. */
    String money(BigDecimal amount, Locale locale);

    /** {@code Rs 1,234.50}: the money with the currency word of the language. */
    String moneyWithCurrency(BigDecimal amount, Locale locale);

    /** {@code 1,234.567}: a quantity, up to three decimals, trailing zeros kept as given. */
    String quantity(BigDecimal quantity, Locale locale);

    /** {@code 24/09/2026}. */
    String date(LocalDate date);

    /** {@code 24/09/2026 14:05}, in the business time zone. */
    String dateTime(Instant instant);
}
