package lk.coopfed.knoweb.m1party.internal.entity;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class EntityOfficerOwnership {

    private static final String USER_BELONGS_TO_ENTITY = "select security.user_belongs_to_entity(?, ?)";

    private final JdbcTemplate jdbc;

    EntityOfficerOwnership(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean userBelongsToEntity(UUID userId, UUID entityId) {

        Boolean result = jdbc.queryForObject(USER_BELONGS_TO_ENTITY, Boolean.class, userId, entityId);

        return Boolean.TRUE.equals(result);
    }
}
