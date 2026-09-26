package lk.coopfed.knoweb.kernel.internal.event;

/**
 * A message that fails the same way on every delivery (a consumer/type pair nobody registered, an
 * envelope without an owner entity, a payload that is not JSON): retrying it only blocks the queue
 * behind it, so the consumer runtime sends it to {@code domain.dlq} at once.
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message) {
        super(message);
    }

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
