package lk.coopfed.knoweb.m1party.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.CreateUser;
import lk.coopfed.knoweb.m1party.api.CredentialResetResult;
import lk.coopfed.knoweb.m1party.api.DeactivateUser;
import lk.coopfed.knoweb.m1party.api.ResetCredential;
import lk.coopfed.knoweb.m1party.api.UpdateUser;
import lk.coopfed.knoweb.m1party.query.UserFilter;
import lk.coopfed.knoweb.m1party.query.UserQueries;
import lk.coopfed.knoweb.m1party.query.UserView;
import lk.coopfed.knoweb.m1party.web.generated.CreateUserRequest;
import lk.coopfed.knoweb.m1party.web.generated.CredentialResetResponse;
import lk.coopfed.knoweb.m1party.web.generated.ResetCredentialRequest;
import lk.coopfed.knoweb.m1party.web.generated.UpdateUserRequest;
import lk.coopfed.knoweb.m1party.web.generated.UserPage;
import lk.coopfed.knoweb.m1party.web.generated.UserReasonRequest;
import lk.coopfed.knoweb.m1party.web.generated.UserResponse;
import lk.coopfed.knoweb.m1party.web.generated.UsersApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UsersController implements UsersApi {

    private static final String VIEW = "gov.user.view";

    private final Handles<CreateUser, UUID> createUser;
    private final Handles<UpdateUser, UUID> updateUser;
    private final Handles<ResetCredential, CredentialResetResult> resetCredential;
    private final Handles<DeactivateUser, UUID> deactivateUser;
    private final UserQueries queries;
    private final CurrentScope currentScope;
    private final PermissionResolver permissions;
    private final boolean enforcePermissions;

    UsersController(
            Handles<CreateUser, UUID> createUser,
            Handles<UpdateUser, UUID> updateUser,
            Handles<ResetCredential, CredentialResetResult> resetCredential,
            Handles<DeactivateUser, UUID> deactivateUser,
            UserQueries queries,
            CurrentScope currentScope,
            PermissionResolver permissions,
            @Value("${coop-erp.security.enforce-permissions:false}") boolean enforcePermissions) {
        this.createUser = createUser;
        this.updateUser = updateUser;
        this.resetCredential = resetCredential;
        this.deactivateUser = deactivateUser;
        this.queries = queries;
        this.currentScope = currentScope;
        this.permissions = permissions;
        this.enforcePermissions = enforcePermissions;
    }

    /**
     * The reads declare x-permission gov.user.view, and no command interceptor runs for a read, so the
     * controller checks it (the pattern of M2's CatalogueController): for the OWN class (the
     * read-only classes resolve no permission; row-level security is what limits them), and only
     * when enforcement is on, as for commands (coop-erp.security.enforce-permissions).
     */
    private void requireView(ScopeContext scope) {
        if (!enforcePermissions || scope == null || scope.policyClass() != PolicyClass.OWN) {
            return;
        }
        if (!permissions.allows(scope, VIEW)) {
            throw new ProblemException("permission.denied", Map.of("permission", VIEW));
        }
    }

    @Override
    public ResponseEntity<UserResponse> createUser(String idempotencyKey, CreateUserRequest request) {
        ScopeContext scope = currentScope.get();
        UUID userId = createUser.handle(
                new CreateUser(
                        request.getHomeEntityId(),
                        request.getUsername(),
                        request.getDisplayName(),
                        request.getLanguage().getValue(),
                        request.getUserKind().getValue(),
                        request.getSucceedsUserId()),
                scope);
        UserView created = queries.getUser(userId, scope).orElseThrow();
        return ResponseEntity.created(URI.create(UsersApi.PATH_LIST_USERS + "/" + userId))
                .body(toResponse(created));
    }

    @Override
    public ResponseEntity<UserResponse> updateUser(UUID userId, String idempotencyKey, UpdateUserRequest request) {
        ScopeContext scope = currentScope.get();
        updateUser.handle(
                new UpdateUser(
                        userId,
                        request.getDisplayName(),
                        request.getLanguage().getValue(),
                        request.getUserKind().getValue()),
                scope);
        return ResponseEntity.ok(toResponse(queries.getUser(userId, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<CredentialResetResponse> resetCredential(
            UUID userId, String idempotencyKey, ResetCredentialRequest request) {
        CredentialResetResult result = resetCredential.handle(
                new ResetCredential(userId, request.getCredential().getValue(), request.getPin()), currentScope.get());
        CredentialResetResponse response = new CredentialResetResponse(
                result.userId(),
                CredentialResetResponse.CredentialEnum.fromValue(result.credential()),
                CredentialResetResponse.StatusEnum.fromValue(result.status()),
                CredentialResetResponse.DeliveryEnum.fromValue(result.delivery()));
        response.setTemporaryPassword(result.temporaryPassword());
        // A one-time password must not be kept by a browser or a proxy.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @Override
    public ResponseEntity<Void> deactivateUser(UUID userId, String idempotencyKey, UserReasonRequest request) {
        deactivateUser.handle(
                new DeactivateUser(userId, request.getReasonCode(), request.getReasonText()), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<UserResponse> getUser(UUID userId) {
        ScopeContext scope = currentScope.get();
        requireView(scope);
        return queries.getUser(userId, scope)
                .map(UsersController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<UserPage> listUsers(String status, String userKind, UUID cursor, Integer limit) {
        ScopeContext scope = currentScope.get();
        requireView(scope);
        lk.coopfed.knoweb.m1party.query.UserPage page =
                queries.listUsers(new UserFilter(status, userKind, cursor, limit), scope);
        List<UserResponse> items =
                page.items().stream().map(UsersController::toResponse).toList();
        UserPage response = new UserPage(items);
        response.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(response);
    }

    private static UserResponse toResponse(UserView view) {
        UserResponse response = new UserResponse(
                view.userId(),
                view.homeEntityId(),
                view.username(),
                view.displayName(),
                UserResponse.LanguageEnum.fromValue(view.language()),
                UserResponse.UserKindEnum.fromValue(view.userKind()),
                UserResponse.StatusEnum.fromValue(view.status()),
                view.pinSet());
        response.setPinChangedAt(view.pinChangedAt());
        response.setSucceedsUserId(view.succeedsUserId());
        return response;
    }
}
