package lk.coopfed.knoweb.kernel.internal.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
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
 */
@Component
class S3ObjectStore implements ObjectStore {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    S3ObjectStore(
            @Value("${coop-erp.object-store.endpoint}") String endpoint,
            @Value("${coop-erp.object-store.public-endpoint}") String publicEndpoint,
            @Value("${coop-erp.object-store.region}") String region,
            @Value("${coop-erp.object-store.access-key}") String accessKey,
            @Value("${coop-erp.object-store.secret-key}") String secretKey,
            @Value("${coop-erp.object-store.attachments-bucket}") String bucket) {
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
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
    public URI presignPut(String key, String contentType, Duration validFor) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        String signed = presigner
                .presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(validFor)
                        .putObjectRequest(put)
                        .build())
                .url()
                .toString();
        return URI.create(signed).normalize();
    }

    @Override
    public URI presignGet(String key, Duration validFor) {
        GetObjectRequest get =
                GetObjectRequest.builder().bucket(bucket).key(key).build();
        String signed = presigner
                .presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(validFor)
                        .getObjectRequest(get)
                        .build())
                .url()
                .toString();
        return URI.create(signed).normalize();
    }

    @Override
    public Optional<Long> head(String key) {
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
}
