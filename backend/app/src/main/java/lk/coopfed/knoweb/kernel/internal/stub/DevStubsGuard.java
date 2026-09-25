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
 * <p>The stubs are right for development and wrong anywhere else: a request that presents no
 * token may still name its user and scope in headers that anybody can send
 * (DevScopeContextProvider, until the second K-02 pull request). 19A replaces the stubs ticket
 * by ticket; this class goes with the last one.
 */
@Component
public class DevStubsGuard {

    private static final Logger log = LoggerFactory.getLogger(DevStubsGuard.class);

    static final String MESSAGE = "The kernel runs on its 17A development stubs: a request without a"
            + " bearer token may still take its identity and scope from request headers.";

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
