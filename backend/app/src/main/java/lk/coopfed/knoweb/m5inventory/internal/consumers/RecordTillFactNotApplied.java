package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.util.UUID;

/**
 * A stock fact a till uploaded that M5 cannot apply yet (wave 2), read by {@link
 * TillFactNotAppliedConsumer}.
 *
 * @param factType   the till's event type
 * @param documentId the document the bundle names, or null
 */
record RecordTillFactNotApplied(String factType, UUID documentId) {}
