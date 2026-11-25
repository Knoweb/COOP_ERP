package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/**
 * Command to close an expiry task (doc 25 section 4.6).
 */
public record CloseExpiryTask(UUID taskId, String reason) {}
