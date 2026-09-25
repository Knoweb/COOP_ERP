package lk.coopfed.knoweb.kernel.internal.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.IdentityProviderClient;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link IdentityProviderClient} over the provider's administration REST API (19A section 2,
 * "KeycloakAdminClient uses a service account limited to the users of the realm"). The
 * service account is a confidential client of the realm holding {@code manage-users} and, since
 * K-08, {@code manage-clients} for the tills' clients ({@link KeycloakDeviceCredentials}); its
 * credentials are configuration, {@code coop-erp.security.oidc.admin.*}.
 *
 * <p>The API used is the plain administration REST API of the provider; no provider library
 * is on the class path, and the four calls (create a user, update a user, reset a password,
 * end the sessions) are what any OIDC provider's administration API offers, so another
 * provider is another class of the same size.
 *
 * <p>The scope rule (ADR-18) runs before every call: the caller acts in the OWN class of the
 * target user's home entity, or the call is refused as {@code identity.scope}. A target is
 * found by its provider subject in M1's {@code app_user}, read as the federation-wide viewer,
 * because the caller's own row policy is what the rule is about to decide.
 */
@Component
public class KeycloakAdminClient implements IdentityProviderClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    /**
     * The characters a one-time password is drawn from: letters and digits without the ambiguous
     * ones (0 and O, 1, l and I), so that a password read out over the phone survives. Built
     * from the ranges rather than written out, which reads better and does not look like a
     * credential to a secret scanner.
     */
    private static final char[] ONE_TIME_CHARACTERS = unambiguousCharacters();

    private static final int PASSWORD_LENGTH = 12;

    private final RestClient rest;
    private final String realmPath;
    private final String clientId;
    private final String clientSecret;
    private final JdbcTemplate jdbc;
    private final SystemScope system;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;

    public KeycloakAdminClient(
            @Value("${coop-erp.security.oidc.admin.base-url}") String baseUrl,
            @Value("${coop-erp.security.oidc.admin.realm}") String realm,
            @Value("${coop-erp.security.oidc.admin.client-id}") String clientId,
            @Value("${coop-erp.security.oidc.admin.client-secret}") String clientSecret,
            JdbcTemplate jdbc,
            SystemScope system,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
        this.realmPath = "/admin/realms/" + realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.jdbc = jdbc;
        this.system = system;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ownTransaction.setReadOnly(true);
        this.clock = clock;
    }

    /** The administration API of the realm, for {@link KeycloakDeviceCredentials}, which shares the service account. */
    RestClient rest() {
        return rest;
    }

    String realmPath() {
        return realmPath;
    }

    @Override
    public String createUser(ScopeContext ctx, UUID userId, UUID homeEntityId, String username, Locale language) {
        assertInScope(ctx, homeEntityId);
        Map<String, Object> user = Map.of(
                "username",
                username,
                "enabled",
                true,
                "attributes",
                // What the realm's token mappers read: uid and the home entity and class the
                // claims mapper needs (ent, cls); without them a new login's tokens carried no
                // class and the user could read and run nothing.
                Map.of(
                        "uid", List.of(userId.toString()),
                        "ent", List.of(homeEntityId.toString()),
                        "cls", List.of(PolicyClass.OWN.name()),
                        "locale", List.of(language == null ? "en" : language.getLanguage())),
                "requiredActions",
                List.of("UPDATE_PASSWORD"));
        try {
            ResponseEntity<Void> created = rest.post()
                    .uri(realmPath + "/users")
                    .headers(this::bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity();
            URI location = created.getHeaders().getLocation();
            if (location == null) {
                throw new ProblemException("identity.unavailable");
            }
            String path = location.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new ProblemException("identity.username_taken", Map.of("username", username));
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void disableUser(ScopeContext ctx, String subjectId) {
        assertInScope(ctx, homeEntityOf(subjectId));
        call(() -> rest.put()
                .uri(realmPath + "/users/{id}", subjectId)
                .headers(this::bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", false))
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public TemporaryPassword setTemporaryPassword(ScopeContext ctx, String subjectId) {
        assertInScope(ctx, homeEntityOf(subjectId));
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(ONE_TIME_CHARACTERS[random.nextInt(ONE_TIME_CHARACTERS.length)]);
        }
        String value = password.toString();
        call(() -> rest.put()
                .uri(realmPath + "/users/{id}/reset-password", subjectId)
                .headers(this::bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", value, "temporary", true))
                .retrieve()
                .toBodilessEntity());
        return new TemporaryPassword(value);
    }

    @Override
    public void resetTotp(ScopeContext ctx, String subjectId) {
        assertInScope(ctx, homeEntityOf(subjectId));
        JsonNode credentials = call(() -> rest.get()
                .uri(realmPath + "/users/{id}/credentials", subjectId)
                .headers(this::bearer)
                .retrieve()
                .body(JsonNode.class));
        if (credentials != null) {
            for (JsonNode credential : credentials) {
                if ("otp".equals(credential.path("type").asText())) {
                    String credentialId = credential.path("id").asText();
                    call(() -> rest.delete()
                            .uri(realmPath + "/users/{id}/credentials/{credential}", subjectId, credentialId)
                            .headers(this::bearer)
                            .retrieve()
                            .toBodilessEntity());
                }
            }
        }
        // The required actions are replaced as a whole by the provider: the ones pending
        // (UPDATE_PASSWORD after a reset) are kept, CONFIGURE_TOTP is added.
        JsonNode user = call(() -> rest.get()
                .uri(realmPath + "/users/{id}", subjectId)
                .headers(this::bearer)
                .retrieve()
                .body(JsonNode.class));
        java.util.LinkedHashSet<String> actions = new java.util.LinkedHashSet<>();
        if (user != null && user.path("requiredActions").isArray()) {
            for (JsonNode action : user.path("requiredActions")) {
                actions.add(action.asText());
            }
        }
        actions.add("CONFIGURE_TOTP");
        call(() -> rest.put()
                .uri(realmPath + "/users/{id}", subjectId)
                .headers(this::bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requiredActions", List.copyOf(actions)))
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public void revokeSessions(ScopeContext ctx, String subjectId) {
        assertInScope(ctx, homeEntityOf(subjectId));
        call(() -> rest.post()
                .uri(realmPath + "/users/{id}/logout", subjectId)
                .headers(this::bearer)
                .retrieve()
                .toBodilessEntity());
    }

    private static char[] unambiguousCharacters() {
        StringBuilder all = new StringBuilder();
        for (char c = 'A'; c <= 'Z'; c++) {
            if (c != 'I' && c != 'O') {
                all.append(c);
            }
        }
        for (char c = 'a'; c <= 'z'; c++) {
            if (c != 'l') {
                all.append(c);
            }
        }
        for (char c = '2'; c <= '9'; c++) {
            all.append(c);
        }
        return all.toString().toCharArray();
    }

    // ---- the scope rule ----

    private static void assertInScope(ScopeContext ctx, UUID homeEntity) {
        if (ctx == null
                || ctx.policyClass() != PolicyClass.OWN
                || homeEntity == null
                || !homeEntity.equals(ctx.entityId())) {
            throw new ProblemException("identity.scope");
        }
        if (ctx.locationId() != null) {
            // User management is the entity's, entity-wide (21A section 6; M1-07 refuses a shop
            // session too): a manager acting at one shop must not reset or disable the
            // administrators of the entity, whose assignments are entity-wide.
            throw new ProblemException("identity.scope");
        }
    }

    /**
     * Read in a transaction of its own (M1-07). The caller is usually a command handler whose
     * transaction has the caller's scope on its connection; running the federation-wide read in
     * that transaction would leave the connection in the viewer's scope for the rest of the
     * handler, and its audit record and event would then be refused by row-level security.
     * The target user is always a committed row by the time a login is disabled or reset.
     */
    private UUID homeEntityOf(String subjectId) {
        List<UUID> found = ownTransaction.execute(status -> system.inScope(
                SystemScope.federationView(),
                () -> jdbc.query(
                        "select home_entity_id from security.app_user where provider_subject = ?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        subjectId)));
        return found == null || found.isEmpty() ? null : found.getFirst();
    }

    // ---- the provider ----

    void bearer(HttpHeaders headers) {
        headers.setBearerAuth(serviceAccountToken());
    }

    private String serviceAccountToken() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenExpiry)) {
            return accessToken;
        }
        synchronized (this) {
            if (accessToken != null && now.isBefore(accessTokenExpiry)) {
                return accessToken;
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            JsonNode token;
            try {
                token = rest.post()
                        .uri(realmPath.replace("/admin/realms/", "/realms/") + "/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientException e) {
                throw unavailable(e);
            }
            if (token == null || !token.path("access_token").isTextual()) {
                throw new ProblemException("identity.unavailable");
            }
            long expiresIn = token.path("expires_in").asLong(60);
            accessToken = token.path("access_token").asText();
            accessTokenExpiry = now.plus(Duration.ofSeconds(Math.max(expiresIn - 30, 5)));
            return accessToken;
        }
    }

    <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private static ProblemException unavailable(RestClientException e) {
        // The status and the message, never a body: a provider's error body can echo the request.
        log.warn("The identity provider did not answer as expected: {}", e.getMessage());
        return new ProblemException("identity.unavailable");
    }
}
