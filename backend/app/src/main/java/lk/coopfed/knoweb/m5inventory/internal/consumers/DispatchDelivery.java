package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.time.Instant;
import java.util.UUID;

/** The vehicle left (delivery_note.dispatched.v1), read by {@link DeliveryConsumer}. */
record DispatchDelivery(UUID deliveryNoteId, UUID sellerEntityId, Instant dispatchedAt) {}
