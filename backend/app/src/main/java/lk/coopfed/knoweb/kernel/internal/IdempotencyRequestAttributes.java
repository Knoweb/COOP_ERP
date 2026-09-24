package lk.coopfed.knoweb.kernel.internal;

public final class IdempotencyRequestAttributes {

    public static final String KEY = IdempotencyRequestAttributes.class.getName() + ".key";

    public static final String REQUEST_HASH = IdempotencyRequestAttributes.class.getName() + ".requestHash";

    private IdempotencyRequestAttributes() {}
}
