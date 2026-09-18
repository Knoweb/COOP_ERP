package lk.coopfed.archfixtures.m1party;

import lk.coopfed.knoweb.kernel.api.CommandHandler;

/** Violates R7: a handler without a permission. */
@CommandHandler(permission = "")
public class BlankPermissionHandler {
}
