package lk.coopfed.knoweb.m1party.internal.queries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.UserFilter;
import lk.coopfed.knoweb.m1party.query.UserPage;
import lk.coopfed.knoweb.m1party.query.UserQueries;
import lk.coopfed.knoweb.m1party.query.UserView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Row-level security decides which users a scope sees; nothing here filters by tenant. */
@Service
@Transactional(readOnly = true)
class UserQueriesImpl implements UserQueries {

    private static final String SELECT =
            """
            select user_id, home_entity_id, username, display_name, language, user_kind, status,
                   pin_hash is not null as pin_set, pin_changed_at, succeeds_user_id
              from security.app_user
            """;

    private final JdbcTemplate jdbc;

    UserQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserView> getUser(UUID userId, ScopeContext scope) {
        if (userId == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " where user_id = ?", UserQueriesImpl::map, userId).stream()
                .findFirst();
    }

    @Override
    public UserPage listUsers(UserFilter filter, ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope()) {
            return new UserPage(List.of(), null);
        }
        UserFilter given = filter == null ? new UserFilter(null, null, null, null) : filter;
        int limit = given.normalizedLimit();

        StringBuilder sql = new StringBuilder(SELECT).append(" where true");
        List<Object> args = new ArrayList<>();
        if (given.status() != null) {
            sql.append(" and status = ?");
            args.add(given.status());
        }
        if (given.userKind() != null) {
            sql.append(" and user_kind = ?");
            args.add(given.userKind());
        }
        if (given.cursor() != null) {
            sql.append(" and user_id > ?");
            args.add(given.cursor());
        }
        sql.append(" order by user_id limit ?");
        args.add(limit + 1);

        List<UserView> rows = jdbc.query(sql.toString(), UserQueriesImpl::map, args.toArray());
        if (rows.size() > limit) {
            List<UserView> page = rows.subList(0, limit);
            return new UserPage(List.copyOf(page), page.get(limit - 1).userId());
        }
        return new UserPage(rows, null);
    }

    private static UserView map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp pinChangedAt = rs.getTimestamp("pin_changed_at");
        return new UserView(
                rs.getObject("user_id", UUID.class),
                rs.getObject("home_entity_id", UUID.class),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("language"),
                rs.getString("user_kind"),
                rs.getString("status"),
                rs.getBoolean("pin_set"),
                pinChangedAt == null ? null : pinChangedAt.toInstant(),
                rs.getObject("succeeds_user_id", UUID.class));
    }
}
