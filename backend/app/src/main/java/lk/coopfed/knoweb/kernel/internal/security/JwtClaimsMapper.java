package lk.coopfed.knoweb.kernel.internal.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The claims of a verified token as a {@link ScopeContext} (doc 19 section 1, the claim table;
 * 19A section 2). The token names who the caller is and where they may act; the request names,
 * in its headers, where they act now.
 *
 * <pre>
 *   sub      the user id (a UUID; the platform issues the ids the provider carries)
 *   dev      the device id, for a till
 *   ent      the home entity
 *   scopes   every "entity" or "entity/location" pair the caller may act in; absent, the
 *            home entity alone, entity-wide (the dev realm; the provider mapper that reads
 *            M1's assignments into the token is the second K-02 pull request)
 *   cls      the policy class; absent or unknown, NONE, and row-level security shows nothing
 *   grants   the entities an EXTERNAL_TIMEBOXED caller may read
 *   mfa_at   when the second factor was last presented, epoch seconds
 *   lang     en, si or ta; absent, the request's Accept-Language; absent too, English
 *   roles    the role ids and rv the catalogue version: the permission resolver reads M1
 *            directly (K-03b), so they are not carried here
 * </pre>
 *
 * The active scope is the X-Scope-Entity and X-Scope-Location headers, or the only scope when
 * there is one. A header naming a scope the token does not carry is refused by the scope
 * filter ({@code scope.invalid}), and a caller with several scopes and no header is asked to
 * choose ({@code scope.required}).
 */
@Component
public class JwtClaimsMapper {

    static final String USER = "sub";
    static final String DEVICE = "dev";
    static final String HOME_ENTITY = "ent";
    static final String SCOPES = "scopes";
    static final String POLICY_CLASS = "cls";
    static final String GRANTS = "grants";
    static final String MFA_AT = "mfa_at";
    static final String LANGUAGE = "lang";

    private static final Set<String> LANGUAGES = Set.of("en", "si", "ta");

    public ScopeContext map(
            Jwt jwt, String activeEntity, String activeLocation, String correlationId, Locale requestLocale) {
        UUID user = uuid(jwt, USER);
        if (user == null) {
            throw new ProblemException("token.invalid");
        }
        UUID device = uuid(jwt, DEVICE);
        UUID homeEntity = uuid(jwt, HOME_ENTITY);
        List<Scope> scopes = scopes(jwt, homeEntity);
        Scope active = active(activeEntity, activeLocation);

        return new ScopeContext(
                user,
                device,
                homeEntity,
                scopes,
                active,
                policyClass(jwt),
                grants(jwt),
                mfaAt(jwt),
                locale(jwt, requestLocale),
                parseUuid(correlationId, Ids.next()));
    }

    private static List<Scope> scopes(Jwt jwt, UUID homeEntity) {
        List<String> claim = jwt.getClaimAsStringList(SCOPES);
        if (claim == null) {
            return homeEntity == null ? List.of() : List.of(new Scope(homeEntity, null));
        }
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

    private static Set<UUID> grants(Jwt jwt) {
        List<String> claim = jwt.getClaimAsStringList(GRANTS);
        if (claim == null) {
            return Set.of();
        }
        Set<UUID> grants = new HashSet<>();
        for (String text : claim) {
            UUID entity = parseUuid(text, null);
            if (entity != null) {
                grants.add(entity);
            }
        }
        return grants;
    }

    private static Instant mfaAt(Jwt jwt) {
        Object value = jwt.getClaim(MFA_AT);
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
