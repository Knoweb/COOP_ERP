package lk.coopfed.knoweb.m9integration.api;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.query.NotificationRequestView;

/**
 * Centralized Notification Gateway (Wave 3, PGL-M3-02).
 *
 * <p>Provides a single, unified entry point for dispatching SMS and message alerts with provider
 * abstraction, configurable routing, state tracking (PENDING, SENT, FAILED), automated retries,
 * deterministic deduplication, and PII-free auditing.
 */
public interface NotificationGateway {

    /**
     * Dispatches a notification request through the configured provider, tracking lifecycle state
     * and enforcing idempotency via deduplication keys.
     */
    NotificationResult dispatch(NotificationRequest request, ScopeContext scope);

    /**
     * Retries delivery for an existing pending or failed notification request without repeating
     * business operations.
     */
    NotificationResult retry(UUID requestId, ScopeContext scope);

    /**
     * Finds a notification request by its unique identifier under caller scope.
     */
    Optional<NotificationRequestView> findById(UUID requestId, ScopeContext scope);

    /**
     * Finds a notification request by its deduplication key within the caller's owner entity scope.
     */
    Optional<NotificationRequestView> findByDedupKey(String dedupKey, ScopeContext scope);
}
