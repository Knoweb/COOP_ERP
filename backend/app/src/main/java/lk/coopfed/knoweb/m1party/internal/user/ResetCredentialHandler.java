package lk.coopfed.knoweb.m1party.internal.user;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.IdentityProviderClient;
import lk.coopfed.knoweb.kernel.api.IdentityProviderClient.TemporaryPassword;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.CredentialResetResult;
import lk.coopfed.knoweb.m1party.api.ResetCredential;
import lk.coopfed.knoweb.m1party.api.UserActivated;
import lk.coopfed.knoweb.m1party.api.UserCredentialReset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ResetCredential (21A section 6; doc 19 section 2.2). Three credentials, one command:
 *
 * <ul>
 *   <li>PASSWORD: the provider sets a one-time password the user must change at the next
 *       sign-in. It goes out by a notification when M9 has a rule for it
 *       ({@link TemporaryPasswordDelivery}); otherwise the administrator receives it once, in
 *       the result, to hand over.</li>
 *   <li>SECOND_FACTOR: the provider forgets the user's second factor; a new one is enrolled at
 *       the next sign-in.</li>
 *   <li>PIN: the new till PIN, checked against the entity's PIN policy and the last PINs,
 *       hashed with Argon2id, the history rotated. The PIN and the hash stay in this method and
 *       the row; the audit record says only that a PIN is set and when.</li>
 * </ul>
 *
 * <p>A PENDING user becomes ACTIVE with its first password or PIN, and a LOCKED one is
 * unlocked by a new one (doc 21 section 4.4). The platform has no callback from the provider for
 * "first credential set", so the issue of the credential is the moment (an M1-07 decision).
 */
@Service
@CommandHandler(permission = "gov.user.manage", requiresMfa = true)
class ResetCredentialHandler implements Handles<ResetCredential, CredentialResetResult> {

    static final String AUDIT_RESET = "USER_CREDENTIAL_RESET";
    static final String AUDIT_ACTIVATED = "USER_ACTIVATED";

    private final AppUserRepository users;
    private final PinPolicy pinPolicy;
    private final IdentityProviderClient provider;
    private final TemporaryPasswordDelivery delivery;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    ResetCredentialHandler(
            AppUserRepository users,
            PinPolicy pinPolicy,
            IdentityProviderClient provider,
            TemporaryPasswordDelivery delivery,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.users = users;
        this.pinPolicy = pinPolicy;
        this.provider = provider;
        this.delivery = delivery;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CredentialResetResult handle(ResetCredential command, ScopeContext scope) {

        UserGuards.requireEntityScope(scope);

        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        AppUser user = UserGuards.find(users, command.userId());
        UserGuards.requireNotDeactivated(user);

        String credential = command.credential();
        boolean password = ResetCredential.PASSWORD.equals(credential);
        boolean secondFactor = ResetCredential.SECOND_FACTOR.equals(credential);
        boolean pin = ResetCredential.PIN.equals(credential);
        if (!password && !secondFactor && !pin) {
            throw new ProblemException("m1.user.credential_invalid");
        }
        if ((password || secondFactor) && !user.hasLogin()) {
            throw new ProblemException("m1.user.password_not_applicable", Map.of("userKind", user.userKind()));
        }
        if ((password || secondFactor) && user.providerSubject() == null) {
            throw new ProblemException("m1.user.no_login", Map.of("userId", user.getId()));
        }
        if (pin) {
            if (!user.hasTillCredential()) {
                throw new ProblemException("m1.user.pin_not_applicable", Map.of("userKind", user.userKind()));
            }
            pinPolicy.check(command.pin(), user.recentPinHashes(), scope);
        }

        Map<String, Object> before = user.auditState();

        String temporaryPassword = null;
        String handedOver = CredentialResetResult.NONE;
        boolean activated = false;

        if (pin) {
            user.setPin(
                    pinPolicy.hash(command.pin()),
                    clock.instant().truncatedTo(ChronoUnit.MICROS),
                    pinPolicy.historyDepth(scope));
            activated = user.activateOnCredential();
        } else if (secondFactor) {
            provider.resetTotp(scope, user.providerSubject());
        } else {
            TemporaryPassword issued = provider.setTemporaryPassword(scope, user.providerSubject());
            activated = user.activateOnCredential();
            if (delivery.deliver(user.homeEntityId(), user.username(), issued.value(), scope)) {
                handedOver = CredentialResetResult.NOTIFIED;
            } else {
                handedOver = CredentialResetResult.RETURNED;
                temporaryPassword = issued.value();
            }
        }
        users.saveAndFlush(user);

        Map<String, Object> after = new LinkedHashMap<>(user.auditState());
        after.put("resetKind", credential);
        after.put("delivery", handedOver);
        Subject subject = Subject.of("user", user.getId());

        audit.record(AUDIT_RESET, subject, before, after, scope);
        if (activated) {
            audit.record(AUDIT_ACTIVATED, subject, before, user.auditState(), scope);
        }

        events.publish(new UserCredentialReset(
                user.getId(), user.getId(), user.homeEntityId(), user.userKind(), user.status(), credential));
        if (activated) {
            events.publish(
                    new UserActivated(user.getId(), user.getId(), user.homeEntityId(), user.userKind(), user.status()));
        }

        return new CredentialResetResult(user.getId(), credential, user.status(), handedOver, temporaryPassword);
    }
}
