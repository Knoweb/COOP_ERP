package lk.coopfed.knoweb.m1party.internal.user;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.IdentityProviderClient;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.DeactivateUser;
import lk.coopfed.knoweb.m1party.api.UserDeactivated;
import lk.coopfed.knoweb.m1party.internal.security.EntityLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DeactivateUser (21A section 6; doc 21 section 4.4). Deactivation is final: a returning member
 * of staff is a new user with {@code succeedsUserId} (doc 21 DR-5). The user's assignments stay
 * as they were, for history; the login is disabled and every session at the provider ended, so
 * the refresh tokens stop working; the event empties the kernel's caches of the user and drops
 * the user from the till's operator snapshot at the next sync.
 *
 * <p>Guards, in order: in the caller's entity; not already deactivated; a reason; not the last
 * holder of gov.user.manage in the entity (21A: Grant.lastAdminGuard; the entity would have
 * nobody left to manage its users, and the Federation holds no downward administration to
 * repair it, ADR-18); not the entity's responsible officer (doc 21 DR-1: the entity must name
 * another one first). "No open till session" waits for M6's query, as in M1-05.
 */
@Service
@CommandHandler(permission = "gov.user.manage", requiresMfa = true)
class DeactivateUserHandler implements Handles<DeactivateUser, UUID> {

    static final String AUDIT_DEACTIVATED = "USER_DEACTIVATED";

    private final AppUserRepository users;
    private final UserFacts facts;
    private final EntityLock lock;
    private final IdentityProviderClient provider;
    private final AuditFacade audit;
    private final EventPublisher events;

    DeactivateUserHandler(
            AppUserRepository users,
            UserFacts facts,
            EntityLock lock,
            IdentityProviderClient provider,
            AuditFacade audit,
            EventPublisher events) {
        this.users = users;
        this.facts = facts;
        this.lock = lock;
        this.provider = provider;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(DeactivateUser command, ScopeContext scope) {

        UserGuards.requireEntityScope(scope);

        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        AppUser user = UserGuards.find(users, command.userId());
        UserGuards.requireNotDeactivated(user);

        if (command.reasonCode() == null || command.reasonCode().isBlank()) {
            throw new ProblemException("m1.user.reason_required");
        }

        UUID entityId = user.homeEntityId();
        // The last-manager guard counts and then writes: two deactivations at once could each see
        // the other manager and both commit. One security command of the entity at a time.
        lock.lock(entityId);
        if (facts.holdsUserManage(user.getId(), entityId) && !facts.anotherUserManagerExists(user.getId(), entityId)) {
            throw new ProblemException("m1.user.last_user_manager", Map.of("userId", user.getId()));
        }
        if (facts.isResponsibleOfficer(user.getId(), entityId)) {
            throw new ProblemException("m1.user.responsible_officer", Map.of("userId", user.getId()));
        }

        Map<String, Object> before = user.auditState();

        user.deactivate();
        users.saveAndFlush(user);

        if (user.providerSubject() != null) {
            provider.disableUser(scope, user.providerSubject());
            provider.revokeSessions(scope, user.providerSubject());
        }

        audit.record(
                AUDIT_DEACTIVATED,
                Subject.of("user", user.getId()),
                before,
                user.auditState(),
                scope,
                reason(command.reasonCode(), command.reasonText()));

        events.publish(new UserDeactivated(user.getId(), user.getId(), entityId, user.userKind(), user.status()));

        return user.getId();
    }

    private static String reason(String reasonCode, String reasonText) {
        String code = reasonCode.strip();
        return reasonText == null || reasonText.isBlank() ? code : code + ": " + reasonText.strip();
    }
}
