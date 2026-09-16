package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.BusinessDate;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class CalendarBusinessDate
        implements BusinessDate {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    @Override
    public LocalDate current(
            ScopeContext context) {
        return LocalDate.now(COLOMBO);
    }
}