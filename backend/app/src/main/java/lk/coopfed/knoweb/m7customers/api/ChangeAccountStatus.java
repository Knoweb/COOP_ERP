package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/**
 * SuspendAccount / ReinstateAccount / CloseAccount / ReopenAccount (27A section 6, doc 27 section
 * 4.2; REOPEN from wave 2, CR-27A-1 item 1), with the reason the society gives.
 *
 * @param action SUSPEND, REINSTATE, CLOSE or REOPEN (a CLOSED account back to SUSPENDED, so a till
 *               fact that landed on it can be settled; the officer then REINSTATEs or CLOSEs)
 */
public record ChangeAccountStatus(UUID accountId, String action, String reason) {

    public static final String SUSPEND = "SUSPEND";
    public static final String REINSTATE = "REINSTATE";
    public static final String CLOSE = "CLOSE";
    public static final String REOPEN = "REOPEN";
}
