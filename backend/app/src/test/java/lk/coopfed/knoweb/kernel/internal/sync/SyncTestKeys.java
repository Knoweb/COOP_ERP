package lk.coopfed.knoweb.kernel.internal.sync;

import org.springframework.context.ApplicationContext;

/**
 * Central's snapshot signing key as a till receives it at enrolment, for a module test that
 * drives the {@link lk.coopfed.knoweb.testsupport.TillSimulator} outside this package (the
 * signer is the kernel's own class and stays package-private).
 */
public final class SyncTestKeys {

    private SyncTestKeys() {}

    public static String signingKey(ApplicationContext context) {
        return context.getBean(TillSigner.class).publicKeyBase64();
    }
}
