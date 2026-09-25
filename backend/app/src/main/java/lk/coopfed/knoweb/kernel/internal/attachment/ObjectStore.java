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

    URI presignPut(String key, String contentType, Duration validFor);

    URI presignGet(String key, Duration validFor);

    /** The object's size, when it exists. */
    Optional<Long> head(String key);

    /** SHA-256 of the object's bytes, lower-case hex; the object must exist. */
    String sha256Hex(String key);
}
