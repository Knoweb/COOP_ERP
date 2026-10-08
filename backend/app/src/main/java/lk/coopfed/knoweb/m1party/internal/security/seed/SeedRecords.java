package lk.coopfed.knoweb.m1party.internal.security.seed;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SeedRecords {
    public record PermissionsSeed(List<PermissionData> permissions) {}

    /** {@code limits_schema}: the JSON Schema of a grant's limits (doc 19 section 3.3); null for most codes. */
    public record PermissionData(
            String code,
            String module,
            String description_en,
            String scope,
            Boolean offline_allowed,
            Boolean requires_mfa,
            Map<String, Object> limits_schema) {}

    public record RoleTemplatesSeed(List<RoleTemplateData> templates) {}

    public record RoleTemplateData(
            UUID role_id,
            String name_en,
            String name_si,
            String name_ta,
            String role_class,
            List<String> permissions) {}

    public record SodPairsSeed(List<SodPairData> pairs) {}

    public record SodPairData(UUID id, String permission_a, String permission_b, String mode) {}

    public record ConfigSeed(List<ConfigData> config) {}

    public record ConfigData(String key, String value) {}
}
