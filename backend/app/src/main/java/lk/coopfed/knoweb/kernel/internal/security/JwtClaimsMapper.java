package lk.coopfed.knoweb.kernel.internal.security;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The claims of a verified token as a {@link ScopeContext} (doc 19 section 1, the claim table;
 * 19A section 2). The token names who the caller is; the platform's records name where they
 * may act; the request names, in its headers, where they act now.
 *
 * <pre>
 *   uid      the platform's user id, when the provider carries it as an attribute; else
 *   sub      the provider's subject, which the dev realm issues as the platform's id
 *   dev      the device id, for a till; with cls = DEVICE the whole scope comes from the
 *            device (DeviceScopes: M1's device, its status, its shop) and uid/sub are ignored
 *   ent      the home entity
 *   scopes   every "entity" or "entity/location" pair the caller may act in; absent, the
 *            active role assignments of M1 (UserScopes) plus the home entity, entity-wide
 *   cls      the policy class; absent or unknown, NONE, and row-level security shows nothing
 *   grants   the entities an EXTERNAL_TIMEBOXED caller may read; absent, M1's active grants
 *   mfa_at   when the second factor was last presented, epoch seconds
 *   lang     en, si or ta; absent, the request's Accept-Language; absent too, English
 *   roles    the role ids and rv the catalogue version: the permission resolver reads M1
 *            directly (K-03b), so they are not carried here
 * </pre>
 *
 * The active scope is the X-Scope-Entity and X-Scope-Location headers, or the only scope when
 * there is one. A header naming a scope the caller does not hold is refused by the scope
 * filter ({@code scope.invalid}), and a caller with several scopes and no header is asked to
 * choose ({@code scope.required}).
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
    static final String LANGUAGE = "lang";

    private static final Set<String> LANGUAGES = Set.of("en", "si", "ta");

    private final UserScopes userScopes;
    private final DeviceScopes deviceScopes;

    public JwtClaimsMapper(UserScopes userScopes, DeviceScopes deviceScopes) {
        this.userScopes = userScopes;
        this.deviceScopes = deviceScopes;
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
        List<Scope> scopes = scopes(jwt, user, homeEntity);
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

    private List<Scope> scopes(Jwt jwt, UUID user, UUID homeEntity) {
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
        // The platform's records, then the home entity: a user with no assignment yet still
        // reads what the OWN class of the home entity shows, and no command runs without a
        // permission (K-03b).
        Set<Scope> resolved = new LinkedHashSet<>();
        if (homeEntity != null) {
            resolved.add(new Scope(homeEntity, null));
        }
        resolved.addAll(userScopes.scopesOf(user));
        return List.copyOf(resolved);
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

    private Set<UUID> grants(Jwt jwt, UUID user, PolicyClass policyClass) {
        List<String> claim = jwt.getClaimAsStringList(GRANTS);
        if (claim == null) {
            return policyClass == PolicyClass.EXTERNAL_TIMEBOXED ? userScopes.grantsOf(user) : Set.of();
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
