package lk.coopfed.archfixtures.m4trading.internal;

import java.util.UUID;
import lk.coopfed.archfixtures.m2catalogue.api.RegisterStock;
import lk.coopfed.archfixtures.m2catalogue.api.StockRegistration;
import lk.coopfed.knoweb.kernel.api.CommandHandler;

/**
 * Violates CR-19A-6: M4's internal command hiding behind M2's api interface. An internal command
 * implements an interface of its own module's api package.
 */
@CommandHandler(permission = CommandHandler.INTERNAL)
public class InternalBehindAnotherModulesApi implements StockRegistration {

    @Override
    public UUID register(RegisterStock command) {
        return null;
    }
}
