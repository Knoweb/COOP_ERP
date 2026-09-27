package lk.coopfed.archfixtures.m2catalogue.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lk.coopfed.archfixtures.m2catalogue.api.RegisterStock;
import lk.coopfed.archfixtures.m2catalogue.api.StockRegistration;
import lk.coopfed.knoweb.kernel.api.CommandHandler;

/** An internal command done right: it implements an interface of its own module's api package. */
@CommandHandler(permission = CommandHandler.INTERNAL)
public class RegisterStockHandler implements StockRegistration {

    @Override
    public UUID register(RegisterStock command) {
        return UUID.nameUUIDFromBytes(command.code().getBytes(StandardCharsets.UTF_8));
    }
}
