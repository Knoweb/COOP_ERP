package lk.coopfed.archfixtures.m2catalogue;

import lk.coopfed.knoweb.kernel.internal.CommandInterceptor;

/**
 * Deliberately illegal dependency used by architecture tests to prove that
 * feature modules may not depend on kernel internals.
 */
public class UsesKernelInternals {

    private CommandInterceptor interceptor;

    public CommandInterceptor interceptor() {
        return interceptor;
    }
}
