package lk.coopfed.knoweb.kernel.api;

import java.util.Locale;

public interface Messages {

    String t(
            String id,
            Locale locale,
            Object... args);
}
