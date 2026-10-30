package lk.coopfed.knoweb.testsupport;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * One Keycloak container for every integration test that needs the provider of the local stack,
 * started once per JVM and shared the way {@link PostgresIntegrationTest#POSTGRES} is: each test
 * class imports the same realm file (infra/compose/realm-dev.json) into the same "coop" realm and
 * only ever creates users, clients or credentials of its own inside it (random UUIDs), so sharing
 * the server changes nothing a test asserts. Before this, {@code KeycloakAdminClientIntegrationTest},
 * {@code KeycloakDeviceCredentialsIntegrationTest} and {@code UsersAgainstTheProviderIntegrationTest}
 * each started their own container (about 85s, 54s and 84s locally): the image pull and realm
 * import happened three times for identical content.
 */
public final class SharedKeycloak {

    /** Relative to backend/app, the working directory of the Gradle test task. */
    private static final String REALM_FILE = "../../infra/compose/realm-dev.json";

    @SuppressWarnings("resource")
    public static final GenericContainer<?> CONTAINER = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.0")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCopyFileToContainer(MountableFile.forHostPath(REALM_FILE), "/opt/keycloak/data/import/realm-dev.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(4)));

    static {
        CONTAINER.start();

        // Test-only: Docker Desktop may expose the mapped HTTP port
        // as an external address. Keycloak's built-in master realm
        // would otherwise reject the test admin-token request.
        try {
            var result = CONTAINER.execInContainer(
                    "/opt/keycloak/bin/kcadm.sh",
                    "update",
                    "realms/master",
                    "-s",
                    "sslRequired=NONE",
                    "--no-config",
                    "--server",
                    "http://localhost:8080",
                    "--realm",
                    "master",
                    "--user",
                    "admin",
                    "--password",
                    "admin");

            if (result.getExitCode() != 0) {
                throw new IllegalStateException(
                        "Could not configure test Keycloak master realm: " + result.getStdout() + result.getStderr());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExceptionInInitializerError(e);
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private SharedKeycloak() {}

    public static String baseUrl() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8080);
    }
}
