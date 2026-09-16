package lk.coopfed.knoweb.kernel.api;

public interface IdempotencyStore {

    /**
     * Returns true only for the first claim of the key.
     */
    boolean claim(String key);

    void release(String key);
}