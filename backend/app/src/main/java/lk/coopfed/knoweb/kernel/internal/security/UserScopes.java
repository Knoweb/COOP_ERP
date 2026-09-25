package lk.coopfed.knoweb.kernel.internal.security;

import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Scope;

/**
 * Where a user may act, from the platform's own records (doc 19 section 1: claims
 * {@code scopes} and the grants of an EXTERNAL_TIMEBOXED principal come from M1's
 * {@code user_role} and {@code external_grant}). The claims mapper asks this when the token
 * does not carry them; {@link JdbcUserScopes} reads M1's tables.
 */
public interface UserScopes {

    /** Every entity/location pair the user holds an active role in; empty for nobody. */
    Set<Scope> scopesOf(UUID userId);

    /** The entities an external, time-boxed grant opens to the user right now. */
    Set<UUID> grantsOf(UUID userId);
}
