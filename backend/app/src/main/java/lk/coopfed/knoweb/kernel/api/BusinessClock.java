package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;

public interface BusinessClock {

    Instant now();
}