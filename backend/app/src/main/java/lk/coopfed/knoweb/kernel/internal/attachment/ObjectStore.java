package lk.coopfed.knoweb.kernel.internal.attachment;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * What the attachment service needs of object storage, and nothing more: a pre-signed PUT, a
 * pre-signed GET, and the two questions the verifier asks. One implementation speaks S3
 * ({@link S3ObjectStore}); the tests keep objects in memory.
 */
interface ObjectStore {

    /**
     * @param contentLength the exact size the client declared, signed into the URL so the store
     *                      refuses any other, or null when the client declared none (the verifier
     *                      still refuses an object over the limit)
     */
    URI presignPut(String key, String contentType, Long contentLength, Duration validFor);

    /**
     * @param contentType what the object was declared as when it was uploaded; a browser is told
     *                    to download anything that is not an image rather than render it from the
     *                    store's origin (Content-Disposition attachment)
     */
    URI presignGet(String key, String contentType, Duration validFor);

    /** The object's size, when it exists. */
    Optional<Long> head(String key);

    /** SHA-256 of the object's bytes, lower-case hex; the object must exist. */
    String sha256Hex(String key);

    /**
     * The object's bytes, for a module that processes what was uploaded (M2's thumbnails,
     * CR-19A-7). The object must exist; one larger than {@code maxBytes} is refused without
     * being held in memory.
     */
    byte[] read(String key, long maxBytes);

    /** Stores bytes the backend produced itself (a thumbnail) under the key, replacing any. */
    void put(String key, String contentType, byte[] bytes);

    /**
     * Removes the object, for the clean-up of an upload that failed verification (kernel V0063);
     * an object that is not there is not an error, so a run that stopped half-way can repeat.
     */
    void delete(String key);
}
