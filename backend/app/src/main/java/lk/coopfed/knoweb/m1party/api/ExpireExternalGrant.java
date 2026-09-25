package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Marks an active grant whose window has passed as EXPIRED (doc 21 section 4.6, trigger "Clock
 * (system)"). Sent by the expiry job, never over HTTP. The grant stopped admitting its holder at
 * {@code valid_until} whether or not this ran; this makes the register and the audit trail say so.
 */
public record ExpireExternalGrant(UUID grantId) {}
