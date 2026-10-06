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
import lk.coopfed.knoweb.m1party.api.UpdateUser;
import lk.coopfed.knoweb.m1party.api.UserUpdated;
import lk.coopfed.knoweb.m1party.internal.security.EntityLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes a user's details (the staff register's edit, doc 21 section 8). A kind may move among
 * BACK_OFFICE, TILL and BOTH; one that stops working a till loses its PIN, and one that stops
 * working the back office (BACK_OFFICE or BOTH to TILL) has its login disabled and its sessions
 * ended at the provider, as DeactivateUser does: the kernel never checks {@code user_kind}, so
 * an enabled login would keep every assigned role usable from the back office. One that comes
 * back to the back office has the login enabled again, with the credentials it had. 21A section
 * 6 names no guard for the kind, so the change is allowed and the login follows it, rather than
 * refused; a change that closes the back office takes DeactivateUser's last-manager guard and the
 * rank rule of {@link UserRank} (CR-21A-7). EXTERNAL is a kind a user is created with and keeps:
 * it decides the policy class, not a detail.
 */
@Service
@CommandHandler(permission = "gov.user.manage", requiresMfa = true)
class UpdateUserHandler implements Handles<UpdateUser, UUID> {

    static final String AUDIT_UPDATED = "USER_UPDATED";

    private final AppUserRepository users;
    private final UserFacts facts;
    private final UserRank rank;
    private final EntityLock lock;
    private final IdentityProviderClient provider;
    private final AuditFacade audit;
    private final EventPublisher events;

    UpdateUserHandler(
            AppUserRepository users,
            UserFacts facts,
            UserRank rank,
            EntityLock lock,
            IdentityProviderClient provider,
            AuditFacade audit,
            EventPublisher events) {
        this.users = users;
        this.facts = facts;
        this.rank = rank;
        this.lock = lock;
        this.provider = provider;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(UpdateUser command, ScopeContext scope) {

        UserGuards.requireEntityScope(scope);

        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        AppUser user = UserGuards.find(users, command.userId());
        UserGuards.requireNotDeactivated(user);

        String displayName = UserGuards.requireDisplayName(command.displayName());
        String language = UserGuards.requireLanguage(command.language());

        String kind = command.userKind();
        if (kind == null || !AppUser.KINDS.contains(kind)) {
            throw new ProblemException("m1.user.kind_invalid");
        }
        if (!kind.equals(user.userKind()) && (user.isExternal() || AppUser.EXTERNAL.equals(kind))) {
            throw new ProblemException("m1.user.kind_change_invalid", Map.of("from", user.userKind(), "to", kind));
        }

        boolean hadLogin = user.hasLogin();
        boolean closesBackOffice = hadLogin && !AppUser.BACK_OFFICE.equals(kind) && !AppUser.BOTH.equals(kind);
        if (closesBackOffice) {
            // Closing the back office disables the login as DeactivateUser does, so it takes
            // DeactivateUser's guards (CR-21A-7): one security command of the entity at a time,
            // because the last-manager guard counts and then writes; never the last user manager;
            // and never a user who holds a sensitive permission the caller does not.
            UUID entityId = user.homeEntityId();
            lock.lock(entityId);
            if (facts.holdsUserManage(user.getId(), entityId)
                    && !facts.anotherUserManagerExists(user.getId(), entityId)) {
                throw new ProblemException("m1.user.last_user_manager", Map.of("userId", user.getId()));
            }
            rank.requireCallerNotOutranked(user, scope);
        }

        Map<String, Object> before = user.auditState();

        user.changeDetails(displayName, language, kind);
        users.saveAndFlush(user);

        if (user.providerSubject() != null && hadLogin != user.hasLogin()) {
            if (hadLogin) {
                // The back office is closed to this user now: the login goes with it, and so
                // do the sessions, so a refresh token does not keep the old roles alive.
                provider.disableUser(scope, user.providerSubject());
                provider.revokeSessions(scope, user.providerSubject());
            } else {
                provider.enableUser(scope, user.providerSubject());
            }
        }

        audit.record(AUDIT_UPDATED, Subject.of("user", user.getId()), before, user.auditState(), scope);

        events.publish(new UserUpdated(user.getId(), user.getId(), user.homeEntityId(), kind, user.status()));

        return user.getId();
    }
}
