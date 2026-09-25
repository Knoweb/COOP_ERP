package lk.coopfed.knoweb.m1party.api;

import java.util.Map;

/**
 * One permission of a role, with its limits (doc 19 section 3.3: "a role can say approve
 * write-offs up to Rs 25,000 without a new permission code"). {@code limits} is null when the
 * permission carries none; otherwise it is checked against the permission's
 * {@code limits_schema} in the catalogue.
 */
public record RolePermission(String permissionCode, Map<String, Object> limits) {

    public RolePermission {
        limits = limits == null || limits.isEmpty() ? null : Map.copyOf(limits);
    }

    public static RolePermission of(String permissionCode) {
        return new RolePermission(permissionCode, null);
    }
}
