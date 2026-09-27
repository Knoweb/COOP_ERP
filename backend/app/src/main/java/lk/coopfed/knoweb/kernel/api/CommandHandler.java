package lk.coopfed.knoweb.kernel.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandHandler {

    /**
     * The permission of an internal command: one that another module's handler calls inside its
     * own command (M2's RegisterBatch from M4's GRN confirmation, doc 22 section 4.1: "internal
     * (caller module)"). It has no operation of its own, so no x-permission; the calling command
     * was checked and holds the request's idempotency key, so the interceptor checks neither
     * again (CR-19A-6).
     *
     * <p>The guard rails, because a handler marked internal is checked by nobody else:
     * <ul>
     *   <li>the interceptor runs an internal command only inside another command handler (one it
     *       has itself intercepted on the same thread) and refuses it everywhere else: a
     *       controller, a job, a consumer or a bare {@code @Transactional} method that calls it
     *       gets an {@link IllegalStateException};
     *   <li>the handler implements an interface in its own module's {@code api} package, which
     *       other modules call; only {@code @CommandHandler} classes may call that interface, and
     *       no class of a {@code web} package may use the handler, the interface or its command
     *       record ({@code ArchitectureTests});
     *   <li>{@code tools/check-permissions.mjs} accepts this constant and a quoted permission code
     *       only, and refuses an OpenAPI operation whose {@code x-permission} is {@code internal}.
     * </ul>
     * An ordinary command is never called inside another one: the interceptor refuses that as a
     * nested command.
     */
    String INTERNAL = "internal";

    String permission();

    boolean requiresMfa() default false;

    String[] requiresAlso() default {};
}
