package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/**
 * SuspendAccount / ReinstateAccount / CloseAccount (27A section 6, doc 27 section 4.2), with the
 * reason the society gives.
 *
 * @param action SUSPEND, REINSTATE or CLOSE
 */
public record ChangeAccountStatus(UUID accountId, String action, String reason) {

    public static final String SUSPEND = "SUSPEND";
    public static final String REINSTATE = "REINSTATE";
    public static final String CLOSE = "CLOSE";
}
