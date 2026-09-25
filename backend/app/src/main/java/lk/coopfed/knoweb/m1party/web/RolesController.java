package lk.coopfed.knoweb.m1party.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.AmendRole;
import lk.coopfed.knoweb.m1party.api.CreateRole;
import lk.coopfed.knoweb.m1party.api.RetireRole;
import lk.coopfed.knoweb.m1party.api.RolePermission;
import lk.coopfed.knoweb.m1party.query.SecurityQueries;
import lk.coopfed.knoweb.m1party.web.generated.AmendRoleRequest;
import lk.coopfed.knoweb.m1party.web.generated.CreateRoleRequest;
import lk.coopfed.knoweb.m1party.web.generated.RoleDiffResponse;
import lk.coopfed.knoweb.m1party.web.generated.RoleList;
import lk.coopfed.knoweb.m1party.web.generated.RolePermissionItem;
import lk.coopfed.knoweb.m1party.web.generated.RoleResponse;
import lk.coopfed.knoweb.m1party.web.generated.RolesApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The role builder's calls (21A section 5; M1-08): create, amend, retire, list, and the template diff. */
@RestController
class RolesController implements RolesApi {

    private final Handles<CreateRole, UUID> createRole;
    private final Handles<AmendRole, UUID> amendRole;
    private final Handles<RetireRole, UUID> retireRole;
    private final SecurityQueries queries;
    private final CurrentScope currentScope;

    RolesController(
            Handles<CreateRole, UUID> createRole,
            Handles<AmendRole, UUID> amendRole,
            Handles<RetireRole, UUID> retireRole,
            SecurityQueries queries,
            CurrentScope currentScope) {
        this.createRole = createRole;
        this.amendRole = amendRole;
        this.retireRole = retireRole;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<RoleResponse> createRole(String idempotencyKey, CreateRoleRequest request) {
        ScopeContext scope = currentScope.get();
        UUID roleId = createRole.handle(
                new CreateRole(
                        request.getNameEn(),
                        request.getNameSi(),
                        request.getNameTa(),
                        request.getTemplateRoleId(),
                        request.getPermissions() == null ? null : permissions(request.getPermissions()),
                        Boolean.TRUE.equals(request.getAsTemplate()),
                        request.getRoleClass() == null
                                ? null
                                : request.getRoleClass().getValue()),
                scope);
        RoleResponse created =
                queries.getRole(roleId, scope).map(RolesController::toResponse).orElseThrow();
        return ResponseEntity.created(URI.create(RolesApi.PATH_CREATE_ROLE + "/" + roleId))
                .body(created);
    }

    @Override
    public ResponseEntity<RoleResponse> amendRole(UUID roleId, String idempotencyKey, AmendRoleRequest request) {
        ScopeContext scope = currentScope.get();
        amendRole.handle(
                new AmendRole(
                        roleId,
                        permissions(request.getPermissions()),
                        Boolean.TRUE.equals(request.getAdoptTemplateVersion())),
                scope);
        return ResponseEntity.ok(
                queries.getRole(roleId, scope).map(RolesController::toResponse).orElseThrow());
    }

    @Override
    public ResponseEntity<Void> retireRole(UUID roleId, String idempotencyKey) {
        retireRole.handle(new RetireRole(roleId), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<RoleList> listRoles() {
        List<RoleResponse> items = queries.listRoles(currentScope.get()).stream()
                .map(RolesController::toResponse)
                .toList();
        return ResponseEntity.ok(new RoleList(items));
    }

    @Override
    public ResponseEntity<RoleResponse> getRole(UUID roleId) {
        return queries.getRole(roleId, currentScope.get())
                .map(RolesController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<RoleDiffResponse> getRoleDiff(UUID roleId) {
        return queries.getRoleDiff(roleId, currentScope.get())
                .map(diff -> new RoleDiffResponse(
                                diff.roleId(),
                                diff.templateRoleId(),
                                diff.templateVersion(),
                                diff.templateUpdated(),
                                diff.onlyInTemplate(),
                                diff.onlyInRole(),
                                diff.limitsDiffer())
                        .templateVersionSeen(diff.templateVersionSeen()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static List<RolePermission> permissions(List<RolePermissionItem> items) {
        return items.stream()
                .map(item -> new RolePermission(item.getPermissionCode(), item.getLimits()))
                .toList();
    }

    private static RoleResponse toResponse(SecurityQueries.RoleView view) {
        List<RolePermissionItem> permissions = view.permissions().stream()
                .map(p -> new RolePermissionItem(p.permissionCode()).limits(p.limits()))
                .toList();
        return new RoleResponse(
                        view.roleId(),
                        view.nameEn(),
                        view.template(),
                        RoleResponse.RoleClassEnum.fromValue(view.roleClass()),
                        view.templateUpdated(),
                        view.version(),
                        RoleResponse.StatusEnum.fromValue(view.status()),
                        permissions)
                .ownerEntityId(view.ownerEntityId())
                .nameSi(view.nameSi())
                .nameTa(view.nameTa())
                .templateRoleId(view.templateRoleId())
                .templateVersionSeen(view.templateVersionSeen())
                .templateVersion(view.templateVersion());
    }
}
