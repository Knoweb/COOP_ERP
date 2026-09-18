package lk.coopfed.archfixtures.m2catalogue;

import lk.coopfed.knoweb.kernel.internal.stub.InMemoryIdempotencyStore;

/** Violates the kernel.api-only rule: a module reaching into kernel.internal. */
public class UsesKernelInternals {

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

    public InMemoryIdempotencyStore store() {
        return store;
    }
}
