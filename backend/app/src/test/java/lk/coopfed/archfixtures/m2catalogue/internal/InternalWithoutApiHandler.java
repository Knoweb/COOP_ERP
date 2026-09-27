package lk.coopfed.archfixtures.m2catalogue.internal;

import lk.coopfed.knoweb.kernel.api.CommandHandler;

/**
 * Violates CR-19A-6: an internal command that implements no interface of its module's api
 * package, so other modules could only reach it through the internal class itself.
 */
@CommandHandler(permission = CommandHandler.INTERNAL)
public class InternalWithoutApiHandler {

    public void handle(String command) {}
}
