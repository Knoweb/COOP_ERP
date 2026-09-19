package lk.coopfed.archfixtures.m1party;

import lk.coopfed.knoweb.kernel.api.CommandHandler;

/**
 * Violates the audit-and-event rule: a command handler that changes data and tells nobody.
 * It has a permission, so rule R7 is content with it; only the new rule catches it.
 */
@CommandHandler(permission = "party.thing.change")
public class SilentHandler {

    private final FixtureRepository repository;

    public SilentHandler(FixtureRepository repository) {
        this.repository = repository;
    }

    public void handle(Object thing) {
        repository.save(thing);
    }
}
