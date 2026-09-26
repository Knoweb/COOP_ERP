package lk.coopfed.knoweb.kernel.internal.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * The store behind the S3 protocol (19A section 9: "provider-neutral, path-style addressing on
 * MinIO"). Two endpoints: the one the backend reaches, and the public one a browser or a till
 * reaches with the pre-signed URL; on a developer machine the first is {@code minio:9000}
 * inside compose and the second {@code localhost:9000}. The signature is computed for the
 * public endpoint, so the URL works where it is used.
 *
 * <p>The credentials have no default: the local stack passes MinIO's (compose), a deployed
 * environment its own bucket-scoped key. Without them the application starts (the store is
 * not needed to serve a page) and says so at ERROR, and every call fails with a clear message
 * rather than a signature the store refuses.
 */
@Component
class S3ObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final boolean configured;

    S3ObjectStore(
            @Value("${coop-erp.object-store.endpoint}") String endpoint,
            @Value("${coop-erp.object-store.public-endpoint}") String publicEndpoint,
            @Value("${coop-erp.object-store.region}") String region,
            @Value("${coop-erp.object-store.access-key}") String accessKey,
            @Value("${coop-erp.object-store.secret-key}") String secretKey,
            @Value("${coop-erp.object-store.attachments-bucket}") String bucket) {
        this.configured = accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
        if (!configured) {
            log.error("No object store credentials (OBJECT_STORE_ACCESS_KEY, OBJECT_STORE_SECRET_KEY):"
                    + " attachments cannot be uploaded, verified or read until they are set");
        }
        AwsCredentialsProvider credentials = configured
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                : AnonymousCredentialsProvider.create();
        S3Configuration pathStyle =
                S3Configuration.builder().pathStyleAccessEnabled(true).build();

        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        this.bucket = bucket;
    }

    @Override
    public URI presignPut(String key, String contentType, Long contentLength, Duration validFor) {
        requireConfigured();
        // A header on the request is part of the signature: the store refuses a PUT with
        // another content type or, when one was declared, another length.
        PutObjectRequest.Builder put =
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType);
        if (contentLength != null) {
            put.contentLength(contentLength);
        }
        String signed = presigner
                .presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(validFor)
                        .putObjectRequest(put.build())
                        .build())
                .url()
                .toString();
        return URI.create(signed).normalize();
    }

    @Override
    public URI presignGet(String key, String contentType, Duration validFor) {
        requireConfigured();
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(bucket).key(key);
        if (contentType == null || !contentType.startsWith("image/")) {
            // Anything but an image (a PDF, or whatever an old row holds) is downloaded, never
            // rendered from the store's origin: response-content-disposition is signed too.
            get.responseContentDisposition("attachment");
        }
        String signed = presigner
                .presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(validFor)
                        .getObjectRequest(get.build())
                        .build())
                .url()
                .toString();
        return URI.create(signed).normalize();
    }

    @Override
    public Optional<Long> head(String key) {
        requireConfigured();
        try {
            return Optional.of(client.headObject(
                            HeadObjectRequest.builder().bucket(bucket).key(key).build())
                    .contentLength());
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        }
    }

    @Override
    public String sha256Hex(String key) {
        requireConfigured();
        try (InputStream bytes = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = bytes.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read object " + key + " from the store", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException(
                    "The object store has no credentials: set OBJECT_STORE_ACCESS_KEY and OBJECT_STORE_SECRET_KEY");
        }
    }
}
