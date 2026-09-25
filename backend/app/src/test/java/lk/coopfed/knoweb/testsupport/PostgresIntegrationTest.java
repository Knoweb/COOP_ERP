package lk.coopfed.knoweb.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base class of every integration test: the whole application on a random port, against a
 * real PostgreSQL 16 started in Docker by Testcontainers. Extend it; do not copy it.
 *
 * <p>Why a real database: row-level security, grants and constraints are the rules under
 * test, and no in-memory database has them.
 *
 * <p>The database is set up exactly like the one of {@code make up}: the container runs the
 * same role script (infra/compose/postgres/init/01-roles.sh), so the application connects as
 * {@code coop_app}, a member of {@code app_rw} and not a superuser, and Flyway migrates as
 * {@code coop_migrator}. A test that passed only because it ran as a superuser would prove
 * nothing, since a superuser bypasses row-level security.
 *
 * <p>One container serves all test classes of a run (it is started once, in the static
 * block, and Testcontainers removes it when the JVM ends). Tests therefore clean the tables
 * they use in a {@code @BeforeEach}, through {@link #superuserJdbc()}.
 *
 * <p>The tag keeps these tests out of {@code make test}; {@code make test-int} runs them.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({KernelRecorder.class, KernelRecording.class})
public abstract class PostgresIntegrationTest {

    private static final String DATABASE = "coop_erp";
    private static final String MIGRATOR = "coop_migrator";
    private static final String APP_USER = "coop_app";
    private static final String RELAY_USER = "coop_relay";
    private static final String RELAY_PASSWORD = "coop_relay";

    /** Relative to backend/app, the working directory of the Gradle test task. */
    private static final String ROLE_SCRIPT = "../../infra/compose/postgres/init/01-roles.sh";

    /**
     * Every audit record and event the application produces during a test, sorted by whether
     * its transaction committed. Every handler test asserts on it; see {@link KernelRecorder}.
     */
    @Autowired
    protected KernelRecorder kernel;

    @BeforeEach
    void forgetWhatEarlierTestsRecorded() {
        kernel.reset();
    }

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName(DATABASE)
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("POSTGRES_INITDB_ARGS", "--locale-provider=icu --icu-locale=en")
            .withEnv("MIGRATION_DB_USER", MIGRATOR)
            .withEnv("MIGRATION_DB_PASSWORD", MIGRATOR)
            .withEnv("APP_DB_USER", APP_USER)
            .withEnv("APP_DB_PASSWORD", APP_USER)
            .withEnv("RELAY_DB_USER", RELAY_USER)
            .withEnv("RELAY_DB_PASSWORD", RELAY_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(ROLE_SCRIPT), "/docker-entrypoint-initdb.d/01-roles.sh")
            // One container serves every test class of a run, and Spring keeps one application
            // context per distinct configuration (a @DynamicPropertySource of its own makes one),
            // each holding its pool of ten. At the default hundred slots a full run filled the
            // server and the last contexts failed to start with "remaining connection slots are
            // reserved". A test database, not a sizing decision.
            .withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_USER);
        registry.add("coop-erp.migration.url", POSTGRES::getJdbcUrl);
        registry.add("coop-erp.migration.user", () -> MIGRATOR);
        registry.add("coop-erp.migration.password", () -> MIGRATOR);
        registry.add("coop-erp.relay.url", POSTGRES::getJdbcUrl);
        registry.add("coop-erp.relay.user", () -> RELAY_USER);
        registry.add("coop-erp.relay.password", () -> RELAY_PASSWORD);
        registry.add("management.health.rabbit.enabled", () -> "false");
        // K-02: every request under /v1 carries a bearer token; the tests sign theirs here.
        registry.add("coop-erp.security.oidc.issuer", () -> TestIdentityProvider.ISSUER);
        registry.add("coop-erp.security.oidc.jwk-set-uri", TestIdentityProvider::jwkSetUri);
    }

    /**
     * A connection as the PostgreSQL superuser, for arranging and inspecting data around the
     * application: it sees every row and may truncate. Never use it for the behaviour under
     * test; that must go through the application, which connects as {@code coop_app}.
     */
    protected static JdbcTemplate superuserJdbc() {
        return new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
