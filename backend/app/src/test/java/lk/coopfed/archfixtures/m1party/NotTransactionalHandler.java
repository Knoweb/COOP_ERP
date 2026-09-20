package lk.coopfed.archfixtures.m1party;

import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

import java.util.UUID;

/**
 * Violates the transaction rule: a command handler whose handle method is not @Transactional.
 * Each repository call would commit by itself, and the scope aspect would never run.
 */
@CommandHandler(permission = "party.thing.change")
public class NotTransactionalHandler implements Handles<UUID, UUID> {

    @Override
    public UUID handle(UUID command, ScopeContext context) {
        return command;
    }
}
