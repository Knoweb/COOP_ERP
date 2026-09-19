package lk.coopfed.archfixtures.m1party;

import lk.coopfed.knoweb.kernel.api.CommandHandler;

/** Violates R7: the permission is still the placeholder that make new-module wrote. */
@CommandHandler(permission = "todo.party.thing.register")
public class PlaceholderPermissionHandler {
}
