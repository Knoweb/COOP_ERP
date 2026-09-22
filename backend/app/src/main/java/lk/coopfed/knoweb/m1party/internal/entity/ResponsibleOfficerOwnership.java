package lk.coopfed.knoweb.m1party.internal.entity;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ResponsibleOfficerOwnership {

    private final JdbcTemplate jdbc;

    ResponsibleOfficerOwnership(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean belongsToEntity(UUID userId, UUID entityId) {

        Boolean result = jdbc.queryForObject(
                """
                select security.user_belongs_to_entity(?, ?)
                """,
                Boolean.class,
                userId,
                entityId);

        return Boolean.TRUE.equals(result);
    }
}
