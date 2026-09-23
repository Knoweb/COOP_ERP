package lk.coopfed.knoweb.kernel.internal.security;

import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPermissionResolver implements PermissionResolver {

    private final JdbcTemplate jdbc;

    public JdbcPermissionResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean allows(ScopeContext ctx, String permission) {

        if (ctx == null
                || !ctx.hasActiveScope()
                || ctx.entityId() == null
                || permission == null
                || permission.isBlank()) {
            return false;
        }

        var userId = ctx.userId();

        if (userId == null) {
            return false;
        }

        Boolean allowed = jdbc.queryForObject(
                """
                select security.user_has_permission(?, ?, ?, ?)
                """,
                Boolean.class,
                userId,
                ctx.entityId(),
                ctx.locationId(),
                permission.strip());

        return Boolean.TRUE.equals(allowed);
    }
}
