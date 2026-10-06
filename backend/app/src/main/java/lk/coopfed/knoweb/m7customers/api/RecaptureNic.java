package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/**
 * Record the customer's NIC again from the card they present (wave 2, M7CR-01;
 * {@code docs/progress/deviations/2026-10-06-wave2-keyed-hashes.md} (4)): the way out of a
 * recorded NIC that no longer matches (captured in the old form before the number was
 * canonicalised, or under a key since retired), and the ordinary path of a key rotation. A fresh
 * second factor and a reason, as a limit increase.
 *
 * @param nic the National Identity Card number, as typed; only its hash and last four characters are kept
 */
public record RecaptureNic(UUID customerId, String nic, String reason) {}
