package lk.coopfed.knoweb.m1party.internal.user;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.IdentityProviderClient;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.CreateUser;
import lk.coopfed.knoweb.m1party.api.UserCreated;
import lk.coopfed.knoweb.m1party.internal.entity.FederationCallers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CreateUser (21A section 6): guards, the user row PENDING, the login at the identity provider
 * with the platform's user id on it, the provider's subject stored on the row, audit, event.
 *
 * <p>The provider is called after the row is written and flushed, so that everything the
 * database can refuse (the unique user name above all) is refused before a login exists. If
 * the transaction still fails after the provider answered, the login stays at the provider,
 * enabled but with no password and no platform user behind it, so nobody can sign in with it;
 * its {@code uid} attribute names the user id that never committed.
 */
@Service
@CommandHandler(permission = "gov.user.manage", requiresMfa = true)
class CreateUserHandler implements Handles<CreateUser, UUID> {

    static final String AUDIT_CREATED = "USER_CREATED";

    /** Letters, digits, dot, dash and underscore; the provider compares names in lower case. */
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");

    private final AppUserRepository users;
    private final UserFacts facts;
    private final FederationCallers federation;
    private final IdentityProviderClient provider;
    private final AuditFacade audit;
    private final EventPublisher events;

    CreateUserHandler(
            AppUserRepository users,
            UserFacts facts,
            FederationCallers federation,
            IdentityProviderClient provider,
            AuditFacade audit,
            EventPublisher events) {
        this.users = users;
        this.facts = facts;
        this.federation = federation;
        this.provider = provider;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CreateUser command, ScopeContext scope) {

        UserGuards.requireEntityScope(scope);

        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        UUID homeEntityId = command.homeEntityId() == null ? scope.entityId() : command.homeEntityId();
        if (!homeEntityId.equals(scope.entityId())) {
            // ADR-18: an administrator creates users of its own entity only; the Federation holds
            // no downward administration.
            throw new ProblemException("m1.user.home_entity_out_of_scope", Map.of("homeEntityId", homeEntityId));
        }

        String kind = command.userKind();
        if (kind == null || !AppUser.KINDS.contains(kind)) {
            throw new ProblemException("m1.user.kind_invalid");
        }
        if (AppUser.EXTERNAL.equals(kind)) {
            requireFederation(scope);
        }

        String language = UserGuards.requireLanguage(command.language());

        String username =
                command.username() == null ? "" : command.username().strip().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw new ProblemException("m1.user.username_invalid");
        }
        if (facts.usernameTaken(username)) {
            throw new ProblemException("m1.user.username_taken", Map.of("username", username));
        }

        String displayName = UserGuards.requireDisplayName(command.displayName());

        if (command.succeedsUserId() != null) {
            // doc 21 DR-5: returning staff are a new user linked to the old one, which must be
            // a deactivated user of the same entity.
            AppUser earlier = users.findById(command.succeedsUserId()).orElse(null);
            if (earlier == null || !earlier.isDeactivated()) {
                throw new ProblemException(
                        "m1.user.succeeds_invalid", Map.of("succeedsUserId", command.succeedsUserId()));
            }
        }

        AppUser user = AppUser.create(
                Ids.next(), homeEntityId, username, displayName, language, kind, command.succeedsUserId());
        users.saveAndFlush(user);

        String subject =
                provider.createUser(scope, user.getId(), homeEntityId, username, Locale.forLanguageTag(language));
        user.linkLogin(subject);
        users.saveAndFlush(user);

        audit.record(AUDIT_CREATED, Subject.of("user", user.getId()), null, user.auditState(), scope);

        events.publish(new UserCreated(user.getId(), user.getId(), homeEntityId, kind, user.status()));

        return user.getId();
    }

    private void requireFederation(ScopeContext scope) {
        try {
            federation.require(scope);
        } catch (ProblemException notFederation) {
            throw new ProblemException("m1.user.external_federation_only");
        }
    }
}
