package lk.coopfed.archfixtures.m2catalogue.internal;

import lk.coopfed.archfixtures.m2catalogue.api.RegisterStock;
import lk.coopfed.archfixtures.m2catalogue.api.StockRegistration;

/**
 * Violates CR-19A-6: a job, not a command handler, that calls an internal command; it would run
 * outside any command, with nobody's permission and no idempotency.
 */
public class JobCallingAnInternalCommand {

    private final StockRegistration registration;

    public JobCallingAnInternalCommand(StockRegistration registration) {
        this.registration = registration;
    }

    public void run() {
        registration.register(new RegisterStock("nightly"));
    }
}
