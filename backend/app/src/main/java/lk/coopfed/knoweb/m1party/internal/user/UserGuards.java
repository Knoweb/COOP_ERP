package lk.coopfed.knoweb.m1party.internal.user;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The guards every user command shares. */
final class UserGuards {

    static final Set<String> LANGUAGES = Set.of("en", "si", "ta");

    private UserGuards() {}

    /**
     * User management is done for a whole entity (gov.user.manage is an ENTITY permission), in
     * the OWN class: an entity administrator manages the users whose home entity is theirs,
     * which for an MPCS includes the staff of its shops (ADR-18). A shop-scoped session is
     * refused rather than left to row-level security, which would hide a new user from it.
     */
    static void requireEntityScope(ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope() || scope.entityId() == null) {
            throw new ProblemException("scope.required");
        }
        if (scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
        if (scope.locationId() != null) {
            throw new ProblemException("m1.user.entity_scope_required");
        }
    }

    /** Found under the caller's row-level security, so a user of another entity is not found. */
    static AppUser find(AppUserRepository users, UUID userId) {
        if (userId == null) {
            throw new ProblemException("request.field.required", Map.of("field", "userId"));
        }
        return users.findById(userId)
                .orElseThrow(() -> new ProblemException("m1.user.not_found", Map.of("userId", userId)));
    }

    static void requireNotDeactivated(AppUser user) {
        if (user.isDeactivated()) {
            throw new ProblemException("m1.user.deactivated", Map.of("userId", user.getId()));
        }
    }

    static String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ProblemException("m1.user.display_name_required");
        }
        return displayName.strip();
    }

    static String requireLanguage(String language) {
        if (language == null || !LANGUAGES.contains(language)) {
            throw new ProblemException("m1.user.language_invalid");
        }
        return language;
    }
}
