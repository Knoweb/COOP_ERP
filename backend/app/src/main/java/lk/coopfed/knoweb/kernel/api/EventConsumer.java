package lk.coopfed.knoweb.kernel.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventConsumer {

    String[] types();

    String consumer();

    /**
     * Whose scope the consumer runs in (CR-19A-13): the event owner's, as every consumer did
     * before wave 2, or the counterparty's. See {@link Party}.
     */
    Party party() default Party.OWNER;

    /**
     * The scope a consumer runs in.
     *
     * <p>{@link #OWNER}: the OWN scope of the event's owner, at the event's location.
     *
     * <p>{@link #COUNTERPARTY}: the OWN, entity-wide scope of the payload's {@code
     * counterpartyEntityId}, so that a two-party document (the seller's invoice) gives the other
     * party its own record (the buyer's payable) without the other party clicking anything. The
     * dispatcher enforces three conditions: the event's source is central, never a till's; the
     * payload names a {@code documentId} whose {@code kernel.document} header has that owner and
     * that counterparty (a module cannot write into an arbitrary entity's scope); and the
     * consumer has its own inbox row, in the counterparty's scope, so owner-side and
     * counterparty-side delivery are idempotent separately. An event whose payload names no
     * counterparty is not for the consumer and is passed over. Such a consumer registers for
     * named types, not "*": the envelope form carries no payload fields to check.
     */
    enum Party {
        OWNER,
        COUNTERPARTY
    }
}
