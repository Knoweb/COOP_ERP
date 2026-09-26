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
     */
    String INTERNAL = "internal";

    String permission();

    boolean requiresMfa() default false;

    String[] requiresAlso() default {};
}
