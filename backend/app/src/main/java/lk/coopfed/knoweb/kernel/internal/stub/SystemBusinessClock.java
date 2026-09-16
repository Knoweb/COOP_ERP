package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.BusinessClock;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SystemBusinessClock
        implements BusinessClock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}