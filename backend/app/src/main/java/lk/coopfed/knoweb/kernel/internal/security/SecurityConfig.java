package lk.coopfed.knoweb.kernel.internal.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The resource server of 19A section 2: every bearer token is verified against the provider's
 * JWKS, its issuer and its times, and a refused token is a 401 problem document. The provider
 * is any OIDC provider (AGENTS.md: no code couples to Keycloak); what names it is
 * configuration, {@code coop-erp.security.oidc.*}: the issuer the tokens carry (the public
 * address, the one the browser signed in at) and the JWKS address the backend reaches (inside
 * the compose network another one).
 *
 * <p>What stays open, and why: health and metrics are probed by the orchestrator without a
 * token (doc 35 decides whether the management port is published at all); CORS preflights
 * carry no token by the browser's rules and are answered by the CORS filter before this.
 *
 * <p><b>Until the second K-02 pull request</b> no path demands a token: a request without one
 * reaches the scope layer, which gives it the development headers' user when those are
 * accepted and no user at all otherwise, and a command without a user is refused there
 * ({@code scope.required}). The second pull request turns {@code /v1/**} to
 * {@code authenticated()} and deletes the headers. No session, no CSRF token: a bearer token
 * is the whole credential and the browser never sends it on its own.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemAuthenticationEntryPoint refused) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> {}).authenticationEntryPoint(refused))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(refused));
        return http.build();
    }

    /**
     * Built here and not from {@code spring.security.oauth2.resourceserver.jwt.issuer-uri},
     * because that discovers the JWKS from the issuer's metadata at the issuer's address, and
     * inside compose the backend reaches the provider at another address than the browser does.
     * The keys are fetched on the first token and cached; no token, no fetch, so the tests that
     * send the development headers alone never look for a provider.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${coop-erp.security.oidc.issuer}") String issuer,
            @Value("${coop-erp.security.oidc.jwk-set-uri}") String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator =
                new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), new JwtIssuerValidator(issuer));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
