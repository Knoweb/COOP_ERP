package lk.coopfed.knoweb.kernel.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandHandler {

    String permission();

    boolean requiresMfa() default false;

    String[] requiresAlso() default {};
}