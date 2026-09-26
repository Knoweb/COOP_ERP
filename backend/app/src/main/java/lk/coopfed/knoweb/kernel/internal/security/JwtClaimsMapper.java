package lk.coopfed.knoweb.kernel.internal.security;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The claims of a verified token as a {@link ScopeContext} (doc 19 section 1, the claim table;
 * 19A section 2). The token names who the caller is; the platform's records name where they
 * may act; the request names, in its headers, where they act now.
 *
 * <pre>
 *   uid       the platform's user id, when the provider carries it as an attribute; else
 *   sub       the provider's subject, which the dev realm issues as the platform's id
 *   dev       the device id, for a till; with cls = DEVICE the whole scope comes from the
 *             device (DeviceScopes: M1's device, its status, its shop) and uid/sub are ignored
 *   ent       the home entity
 *   scopes    every "entity" or "entity/location" pair the caller may act in; absent, the
 *             active role assignments of M1 (UserScopes) and nothing else for the OWN class:
 *             a user with no assignment acts nowhere (doc 19 section 3.1, the assignments are
 *             the scope). A read-only class (FEDERATION_VIEW, EXTERNAL_TIMEBOXED) acts from its
 *             home entity, whose rows its policies do not depend on.
 *   cls       the policy class; absent or unknown, NONE, and row-level security shows nothing
 *   grants    the entities an EXTERNAL_TIMEBOXED caller may read, never more than M1's active
 *             grants say now; absent, those grants
 *   mfa_at    when the second factor was last presented, epoch seconds; absent, auth_time when
 *             the token's acr (or amr) says a second factor was used, or, where the platform
 *             accepts a fresh password sign-in as the step-up (development), auth_time itself
 *   lang      en, si or ta; absent, the request's Accept-Language; absent too, English
 *   roles     the role ids and rv the catalogue version: the permission resolver reads M1
 *             directly (K-03b), so they are not carried here
 * </pre>
 *
 * The active scope is the X-Scope-Entity and X-Scope-Location headers, or the only scope when
 * there is one. A header naming a scope the caller does not hold is refused by the scope
 * filter ({@code scope.invalid}; an entity-wide holder may name any location of the entity),
 * and a caller with several scopes and no header is asked to choose ({@code scope.required}).
 */
@Component
public class JwtClaimsMapper {

    static final String PLATFORM_USER = "uid";
    static final String SUBJECT = "sub";
    static final String DEVICE = "dev";
    static final String HOME_ENTITY = "ent";
    static final String SCOPES = "scopes";
    static final String POLICY_CLASS = "cls";
    static final String GRANTS = "grants";
    static final String MFA_AT = "mfa_at";
    static final String AUTH_TIME = "auth_time";
    static final String ACR = "acr";
    static final String AMR = "amr";
    static final String LANGUAGE = "lang";

    private static final Set<String> LANGUAGES = Set.of("en", "si", "ta");
    private static final Logger LOG = LoggerFactory.getLogger(JwtClaimsMapper.class);

    private final UserScopes userScopes;
    private final DeviceScopes deviceScopes;
    private final Set<String> secondFactorAcr;
    private final boolean passwordReauthCounts;

    @Autowired
    public JwtClaimsMapper(
            UserScopes userScopes,
            DeviceScopes deviceScopes,
            @Value("${coop-erp.security.mfa.acr-values:2,loa2,mfa,otp}") List<String> secondFactorAcr,
            @Value("${coop-erp.security.mfa.password-reauth-counts:false}") boolean passwordReauthCounts,
            @Value("${coop-erp.security.oidc.issuer:}") String issuer) {
        this.userScopes = userScopes;
        this.deviceScopes = deviceScopes;
        this.secondFactorAcr = Set.copyOf(secondFactorAcr);
        this.passwordReauthCounts = passwordReauthCounts;
        if (passwordReauthCounts && !isDevelopmentIssuer(issuer)) {
            // A password sign-in counted as the second factor is for a development realm without
            // OTP; with any other issuer it switches the step-up off. Loud, not fatal: an
            // operator may be testing a staging realm on purpose.
            LOG.error(
                    "coop-erp.security.mfa.password-reauth-counts is true with the issuer {}, which is not a"
                            + " development issuer: a password sign-in counts as the second factor. Set it to"
                            + " false outside development.",
                    issuer);
        }
    }

    JwtClaimsMapper(
            UserScopes userScopes,
            DeviceScopes deviceScopes,
            List<String> secondFactorAcr,
            boolean passwordReauthCounts) {
        this(userScopes, deviceScopes, secondFactorAcr, passwordReauthCounts, "http://localhost:8085/realms/coop");
    }

    /** The production rule: only an acr or amr that names a second factor counts. */
    JwtClaimsMapper(UserScopes userScopes, DeviceScopes deviceScopes) {
        this(userScopes, deviceScopes, List.of("2", "loa2", "mfa", "otp"), false);
    }

    /** An issuer on this machine or on a development host name (localhost, *.localhost, *.test). */
    static boolean isDevelopmentIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return false;
        }
        String host;
        try {
            host = URI.create(issuer.trim()).getHost();
        } catch (IllegalArgumentException notAUri) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("[::1]")
                || host.endsWith(".localhost")
                || host.endsWith(".test");
    }

    public ScopeContext map(
            Jwt jwt, String activeEntity, String activeLocation, String correlationId, Locale requestLocale) {
        if (policyClass(jwt) == PolicyClass.DEVICE) {
            // A device token (K-08): the device is the principal, not a user. Its subject is the
            // provider's service account of the device's client, nobody the platform knows; its
            // scope is the device's shop from M1's records, checked for status on every call
            // (DeviceAuth); the scope headers do not apply.
            return deviceScopes.scopeOf(
                    uuid(jwt, DEVICE), parseUuid(correlationId, Ids.next()), locale(jwt, requestLocale));
        }
        UUID user = uuid(jwt, PLATFORM_USER);
        if (user == null) {
            user = uuid(jwt, SUBJECT);
        }
        if (user == null) {
            throw new ProblemException("token.invalid");
        }
        UUID device = uuid(jwt, DEVICE);
        UUID homeEntity = uuid(jwt, HOME_ENTITY);
        PolicyClass policyClass = policyClass(jwt);
        List<Scope> scopes = scopes(jwt, user, homeEntity, policyClass);
        Scope active = active(activeEntity, activeLocation);

        return new ScopeContext(
                user,
                device,
                homeEntity,
                scopes,
                active,
                policyClass,
                grants(jwt, user, policyClass),
                mfaAt(jwt),
                locale(jwt, requestLocale),
                parseUuid(correlationId, Ids.next()));
    }

    private List<Scope> scopes(Jwt jwt, UUID user, UUID homeEntity, PolicyClass policyClass) {
        List<String> claim = jwt.getClaimAsStringList(SCOPES);
        if (claim != null) {
            List<Scope> scopes = new ArrayList<>();
            for (String text : claim) {
                String[] parts = text.split("/", 2);
                UUID entity = parseUuid(parts[0], null);
                if (entity == null) {
                    throw new ProblemException("token.invalid");
                }
                scopes.add(new Scope(entity, parts.length == 2 ? parseUuid(parts[1], null) : null));
            }
            return scopes;
        }
        if (policyClass == PolicyClass.OWN) {
            // The assignments and nothing else: a home entity is where a user belongs, not where
            // they may act. A cashier of one shop reads that shop, never the whole society.
            return List.copyOf(new LinkedHashSet<>(userScopes.scopesOf(user)));
        }
        return homeEntity == null ? List.of() : List.of(new Scope(homeEntity, null));
    }

    private static Scope active(String entity, String location) {
        UUID entityId = parseUuid(entity, null);
        if (entityId == null) {
            return null;
        }
        return new Scope(entityId, parseUuid(location, null));
    }

    private static PolicyClass policyClass(Jwt jwt) {
        String text = jwt.getClaimAsString(POLICY_CLASS);
        if (text == null || text.isBlank()) {
            return PolicyClass.NONE;
        }
        try {
            return PolicyClass.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return PolicyClass.NONE;
        }
    }

    /**
     * Only an EXTERNAL_TIMEBOXED caller has granted entities, and only those M1's register gives
     * it now: a token is issued for its lifetime, a grant is revoked at once (doc 21 section
     * 6.5), so a {@code grants} claim narrows what the records say and never widens it.
     */
    private Set<UUID> grants(Jwt jwt, UUID user, PolicyClass policyClass) {
        if (policyClass != PolicyClass.EXTERNAL_TIMEBOXED) {
            return Set.of();
        }
        Set<UUID> recorded = userScopes.grantsOf(user);
        List<String> claim = jwt.getClaimAsStringList(GRANTS);
        if (claim == null) {
            return recorded;
        }
        Set<UUID> grants = new HashSet<>();
        for (String text : claim) {
            UUID entity = parseUuid(text, null);
            if (entity != null && recorded.contains(entity)) {
                grants.add(entity);
            }
        }
        return grants;
    }

    /**
     * When the second factor was last presented. The explicit claim first; else the standard
     * {@code auth_time}, which is when the user last authenticated at the provider, counted only
     * when the token says that authentication used a second factor ({@code acr} at a level the
     * platform lists, or {@code amr} naming one), or when the platform accepts a fresh password
     * sign-in as the step-up (a development realm without OTP; never outside it).
     */
    Instant mfaAt(Jwt jwt) {
        Instant explicit = instantClaim(jwt.getClaim(MFA_AT));
        if (explicit != null) {
            return explicit;
        }
        Instant authTime = instantClaim(jwt.getClaim(AUTH_TIME));
        if (authTime == null) {
            return null;
        }
        return usedSecondFactor(jwt) || passwordReauthCounts ? authTime : null;
    }

    private boolean usedSecondFactor(Jwt jwt) {
        String acr = jwt.getClaimAsString(ACR);
        if (acr != null && secondFactorAcr.contains(acr.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        List<String> amr = jwt.getClaimAsStringList(AMR);
        return amr != null && amr.stream().anyMatch(m -> secondFactorAcr.contains(m.toLowerCase(Locale.ROOT)));
    }

    private static Instant instantClaim(Object value) {
        if (value instanceof Number seconds) {
            return Instant.ofEpochSecond(seconds.longValue());
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(text.trim()));
            } catch (NumberFormatException notSeconds) {
                try {
                    return Instant.parse(text.trim());
                } catch (RuntimeException notAnInstant) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Locale locale(Jwt jwt, Locale requestLocale) {
        String lang = jwt.getClaimAsString(LANGUAGE);
        if (lang != null && LANGUAGES.contains(lang.trim().toLowerCase(Locale.ROOT))) {
            return Locale.forLanguageTag(lang.trim().toLowerCase(Locale.ROOT));
        }
        if (requestLocale != null && LANGUAGES.contains(requestLocale.getLanguage())) {
            return Locale.forLanguageTag(requestLocale.getLanguage());
        }
        return Locale.ENGLISH;
    }

    private static UUID uuid(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        return value == null ? null : parseUuid(value.toString(), null);
    }

    private static UUID parseUuid(String value, UUID fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw new ProblemException("scope.invalid");
        }
    }
}
