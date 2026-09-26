package lk.coopfed.knoweb.kernel.internal.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * The S3 client against a real store (MinIO in Docker, the image the local stack runs): a
 * pre-signed PUT uploads, HEAD sees it, the hash is the bytes' hash, a pre-signed GET reads
 * it, and a missing key is simply absent. What 19A section 9 calls "provider-neutral,
 * path-style addressing" is proved here, once, against the provider a developer has.
 */
@Tag("integration")
class S3ObjectStoreIntegrationTest {

    private static final String USER = "coopminio";
    private static final String PASSWORD = "coopminio-dev";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
                    "bitnamilegacy/minio:2025.7.23-debian-12-r5")
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
            .withEnv("MINIO_DEFAULT_BUCKETS", "attachments")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

    private static S3ObjectStore store;

    @BeforeAll
    static void startTheStore() {
        MINIO.start();
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        // The local stack creates the buckets with mc (compose, minio-init); here the client does.
        try (S3Client admin = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(USER, PASSWORD)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            admin.createBucket(
                    CreateBucketRequest.builder().bucket("attachments").build());
        } catch (BucketAlreadyOwnedByYouException alreadyThere) {
            // fine
        }
        store = new S3ObjectStore(endpoint, endpoint, "us-east-1", USER, PASSWORD, "attachments");
    }

    @AfterAll
    static void stopTheStore() {
        MINIO.stop();
    }

    @Test
    void aPresignedPutUploadsAndTheStoreAnswersHeadHashAndGet() throws Exception {
        String key = "attachments/test/" + System.nanoTime();
        byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);
        String expected =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        assertThat(store.head(key)).isEmpty();

        URI put = store.presignPut(key, "text/plain", (long) bytes.length, Duration.ofMinutes(5));
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<Void> uploaded = http.send(
                HttpRequest.newBuilder(put)
                        .header("Content-Type", "text/plain")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(uploaded.statusCode()).isEqualTo(200);

        // The length is part of the signature: a PUT of other bytes with the same URL is refused.
        HttpResponse<Void> other = http.send(
                HttpRequest.newBuilder(put)
                        .header("Content-Type", "text/plain")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(
                                "evidence, longer".getBytes(StandardCharsets.UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(other.statusCode()).isEqualTo(403);

        assertThat(store.head(key)).contains((long) bytes.length);
        assertThat(store.sha256Hex(key)).isEqualTo(expected);

        HttpResponse<String> read = http.send(
                HttpRequest.newBuilder(store.presignGet(key, "text/plain", Duration.ofMinutes(5)))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo("evidence");
        // Not an image: the store is told to serve it as a download.
        assertThat(read.headers().firstValue("Content-Disposition")).contains("attachment");
    }
}
