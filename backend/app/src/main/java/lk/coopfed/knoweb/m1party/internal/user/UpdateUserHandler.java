package lk.coopfed.knoweb.m1party.internal.user;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.UpdateUser;
import lk.coopfed.knoweb.m1party.api.UserUpdated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes a user's details (the staff register's edit, doc 21 section 8). A kind may move among
 * BACK_OFFICE, TILL and BOTH; one that stops working a till loses its PIN. EXTERNAL is a kind a
 * user is created with and keeps: it decides the policy class, not a detail.
 */
@Service
@CommandHandler(permission = "gov.user.manage", requiresMfa = true)
class UpdateUserHandler implements Handles<UpdateUser, UUID> {

    static final String AUDIT_UPDATED = "USER_UPDATED";

    private final AppUserRepository users;
    private final AuditFacade audit;
    private final EventPublisher events;

    UpdateUserHandler(AppUserRepository users, AuditFacade audit, EventPublisher events) {
        this.users = users;
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

        Map<String, Object> before = user.auditState();

        user.changeDetails(displayName, language, kind);
        users.saveAndFlush(user);

        audit.record(AUDIT_UPDATED, Subject.of("user", user.getId()), before, user.auditState(), scope);

        events.publish(new UserUpdated(user.getId(), user.getId(), user.homeEntityId(), kind, user.status()));

        return user.getId();
    }
}
