package lk.coopfed.knoweb.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;

/**
 * An identity provider for the tests: a key pair, its JWKS on a local socket, and tokens signed
 * here with the claims of doc 19 section 1. {@link PostgresIntegrationTest} points the
 * application's resource server at it, so every HTTP test signs its requests as a real
 * caller would, with a bearer token, and the claims mapper and the scope filter are exercised
 * on every request. A token from another key, another issuer or the past is refused by the
 * application like any other.
 */
public final class TestIdentityProvider {

    public static final String ISSUER = "http://provider.test/realms/coop";

    private static final RSAKey KEY;
    private static final RSAKey OTHER_KEY;
    private static final String JWK_SET_URI;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            OTHER_KEY = new RSAKeyGenerator(2048).keyID("other-key").generate();
            String jwkSet = new JWKSet(KEY.toPublicJWK()).toString();

            HttpServer jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwks.createContext("/certs", exchange -> {
                byte[] body = jwkSet.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            jwks.start();
            JWK_SET_URI = "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs";
        } catch (Exception e) {
            throw new IllegalStateException("The test identity provider could not start", e);
        }
    }

    private TestIdentityProvider() {}

    public static String jwkSetUri() {
        return JWK_SET_URI;
    }

    /** A token of the OWN class for a user of an entity: what a back-office user carries. */
    public static String token(UUID user, UUID homeEntity) {
        return token(user, homeEntity, "OWN", claims -> {});
    }

    /** A token of a given policy class (OWN, FEDERATION_VIEW, EXTERNAL_TIMEBOXED, NONE). */
    public static String token(UUID user, UUID homeEntity, String policyClass) {
        return token(user, homeEntity, policyClass, claims -> {});
    }

    public static String token(
            UUID user, UUID homeEntity, String policyClass, Consumer<JWTClaimsSet.Builder> customise) {
        return signed(KEY, user, homeEntity, policyClass, customise);
    }

    /** A token nobody should accept: signed by a key the JWKS does not carry. */
    public static String tokenFromAnotherKey(UUID user, UUID homeEntity) {
        return signed(OTHER_KEY, user, homeEntity, "OWN", claims -> {});
    }

    /** Headers of a request as a user of an entity, acting in that entity. */
    public static HttpHeaders headers(UUID user, UUID entity) {
        return headers(user, entity, "OWN");
    }

    public static HttpHeaders headers(UUID user, UUID entity, String policyClass) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(user, entity, policyClass));
        headers.set("X-Scope-Entity", entity.toString());
        return headers;
    }

    private static String signed(
            RSAKey signingKey,
            UUID user,
            UUID homeEntity,
            String policyClass,
            Consumer<JWTClaimsSet.Builder> customise) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(user.toString())
                    .issuer(ISSUER)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("cls", policyClass)
                    .claim("lang", "en");
            if (homeEntity != null) {
                claims.claim("ent", homeEntity.toString());
            }
            customise.accept(claims);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
