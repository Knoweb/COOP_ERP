package lk.coopfed.knoweb.kernel.internal.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Says out loud that this build runs on the 17A kernel stubs, and refuses to run them in
 * production.
 *
 * <p>The stubs are right for development and wrong anywhere else: the caller's identity and
 * scope come from request headers that anybody can send (DevCurrentScope), nothing is
 * authenticated, the idempotency store is a map in one instance's memory, and audit records
 * and events are written to the log and not stored. 19A replaces them ticket by ticket; this
 * class goes with the last one.
 */
@Component
public class DevStubsGuard {

    private static final Logger log = LoggerFactory.getLogger(DevStubsGuard.class);

    static final String MESSAGE = "The kernel runs on its 17A development stubs: identity and scope are"
            + " taken from request headers without authentication, idempotency keys live in this"
            + " instance's memory, audit records and events are logged and not stored.";

    public DevStubsGuard(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException(MESSAGE + " Refusing to start under a production profile.");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warn() {
        log.warn("DEVELOPMENT ONLY. {} Never expose this build outside a developer machine or CI.", MESSAGE);
    }
}
