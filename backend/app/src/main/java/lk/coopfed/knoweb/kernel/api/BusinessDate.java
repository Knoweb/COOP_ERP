package lk.coopfed.knoweb.kernel.api;

import java.time.LocalDate;

public interface BusinessDate {

    LocalDate current(ScopeContext context);
}