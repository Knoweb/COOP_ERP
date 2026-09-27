package lk.coopfed.archfixtures.m2catalogue.web;

import java.util.UUID;
import lk.coopfed.archfixtures.m2catalogue.api.RegisterStock;
import lk.coopfed.archfixtures.m2catalogue.api.StockRegistration;

/**
 * Violates CR-19A-6: a controller that calls an internal command itself, which would then run
 * with no permission check and no idempotency key.
 */
public class ControllerReachingAnInternalCommand {

    private final StockRegistration registration;

    public ControllerReachingAnInternalCommand(StockRegistration registration) {
        this.registration = registration;
    }

    public UUID post(String code) {
        return registration.register(new RegisterStock(code));
    }
}
