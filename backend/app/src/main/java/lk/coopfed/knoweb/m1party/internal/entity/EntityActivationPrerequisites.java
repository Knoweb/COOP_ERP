package lk.coopfed.knoweb.m1party.internal.entity;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class EntityActivationPrerequisites {

    private static final String HAS_USER_MANAGER =
            """
            select security.entity_has_user_manager(?)
            """;

    private final JdbcTemplate jdbc;

    EntityActivationPrerequisites(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean hasUserManager(UUID entityId) {
        Boolean result = jdbc.queryForObject(HAS_USER_MANAGER, Boolean.class, entityId);

        return Boolean.TRUE.equals(result);
    }
}
