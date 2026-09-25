package lk.coopfed.knoweb.kernel.internal.i18n;

import com.ibm.icu.text.DecimalFormat;
import com.ibm.icu.text.DecimalFormatSymbols;
import com.ibm.icu.util.ULocale;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lk.coopfed.knoweb.kernel.api.Formats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The formats of doc 19 section 5.1 on ICU with the LATN numbering system forced (DR-6). The
 * shapes are fixed by the platform, not by the locale: grouping by thousands, a point for the
 * decimals, {@code dd/MM/yyyy}. What the locale gives is the word for the currency.
 */
@Component
class IcuFormats implements Formats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ZoneId zone;

    IcuFormats(@Value("${coop-erp.business-timezone}") String zone) {
        this.zone = ZoneId.of(zone);
    }

    @Override
    public String money(BigDecimal amount, Locale locale) {
        return format(amount.setScale(2, RoundingMode.HALF_UP), "#,##0.00");
    }

    @Override
    public String moneyWithCurrency(BigDecimal amount, Locale locale) {
        String language = locale == null ? "en" : locale.getLanguage();
        String word =
                switch (language) {
                    case "si" -> "රු.";
                    case "ta" -> "ரூ.";
                    default -> "Rs";
                };
        return word + " " + money(amount, locale);
    }

    @Override
    public String quantity(BigDecimal quantity, Locale locale) {
        BigDecimal scaled = quantity.scale() > 3 ? quantity.setScale(3, RoundingMode.HALF_UP) : quantity;
        String pattern = scaled.scale() <= 0 ? "#,##0" : "#,##0." + "0".repeat(scaled.scale());
        return format(scaled, pattern);
    }

    @Override
    public String date(LocalDate date) {
        return DATE.format(date);
    }

    @Override
    public String dateTime(Instant instant) {
        return DATE_TIME.format(instant.atZone(zone));
    }

    /** The LATN symbols of en-LK: Western Arabic digits, a comma to group, a point for decimals. */
    private static String format(BigDecimal value, String pattern) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(ULocale.forLanguageTag("en-LK-u-nu-latn"));
        DecimalFormat format = new DecimalFormat(pattern, symbols);
        format.setRoundingMode(BigDecimal.ROUND_HALF_UP);
        return format.format(value);
    }
}
