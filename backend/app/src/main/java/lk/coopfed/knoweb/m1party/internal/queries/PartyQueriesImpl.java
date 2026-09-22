package lk.coopfed.knoweb.m1party.internal.queries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.EntityFilter;
import lk.coopfed.knoweb.m1party.query.EntityPage;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class PartyQueriesImpl implements PartyQueries {

    private static final String FULL_SELECT =
            """
            select
                entity_id,
                entity_code,
                entity_type,
                legal_name_en,
                legal_name_si,
                legal_name_ta,
                registration_no,
                vat_registration_no,
                district,
                financial_year_start_month,
                default_language,
                responsible_officer_user_id,
                data_governance_signed_on,
                status
            from party.entity
            """;

    private static final String PARTY_SELECT =
            """
            select
                entity_id,
                legal_name_en,
                legal_name_si,
                legal_name_ta
            from party.entity_party_directory
            """;

    private static final RowMapper<EntityView> FULL_MAPPER = PartyQueriesImpl::mapFull;

    private static final RowMapper<EntityView> PARTY_MAPPER = PartyQueriesImpl::mapParty;

    private final JdbcTemplate jdbc;

    PartyQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EntityView> getEntity(UUID entityId, ScopeContext scope) {

        if (entityId == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }

        if (scope.policyClass() == PolicyClass.PARTY) {
            return jdbc
                    .query(
                            PARTY_SELECT
                                    + """
                                     where entity_id = ?
                                    """,
                            PARTY_MAPPER,
                            entityId)
                    .stream()
                    .findFirst();
        }

        return jdbc
                .query(
                        FULL_SELECT
                                + """
                                 where entity_id = ?
                                """,
                        FULL_MAPPER,
                        entityId)
                .stream()
                .findFirst();
    }

    @Override
    public EntityPage listEntities(EntityFilter filter, ScopeContext scope) {

        if (scope == null || !scope.hasActiveScope()) {
            return new EntityPage(List.of(), null);
        }

        EntityFilter effectiveFilter = filter == null ? new EntityFilter(null, null, null, 50) : filter;

        int limit = effectiveFilter.normalizedLimit();

        int fetchLimit = limit + 1;

        if (scope.policyClass() == PolicyClass.PARTY) {
            return listParty(effectiveFilter.cursor(), limit, fetchLimit);
        }

        return listFull(effectiveFilter, limit, fetchLimit);
    }

    private EntityPage listParty(UUID cursor, int limit, int fetchLimit) {

        StringBuilder sql = new StringBuilder(
                PARTY_SELECT + """
                                 where 1 = 1
                                """);

        List<Object> params = new ArrayList<>();

        if (cursor != null) {
            sql.append("""
                     and entity_id > ?
                    """);

            params.add(cursor);
        }

        sql.append("""
                 order by entity_id
                 limit ?
                """);

        params.add(fetchLimit);

        List<EntityView> rows = jdbc.query(sql.toString(), PARTY_MAPPER, params.toArray());

        return page(rows, limit);
    }

    private EntityPage listFull(EntityFilter filter, int limit, int fetchLimit) {

        StringBuilder sql = new StringBuilder(
                FULL_SELECT + """
                                 where 1 = 1
                                """);

        List<Object> params = new ArrayList<>();

        if (filter.status() != null && !filter.status().isBlank()) {

            sql.append("""
                     and status = ?
                    """);

            params.add(filter.status().strip().toUpperCase());
        }

        if (filter.district() != null && !filter.district().isBlank()) {

            sql.append("""
                     and district = ?
                    """);

            params.add(filter.district().strip());
        }

        if (filter.cursor() != null) {
            sql.append("""
                     and entity_id > ?
                    """);

            params.add(filter.cursor());
        }

        sql.append("""
                 order by entity_id
                 limit ?
                """);

        params.add(fetchLimit);

        List<EntityView> rows = jdbc.query(sql.toString(), FULL_MAPPER, params.toArray());

        return page(rows, limit);
    }

    private static EntityPage page(List<EntityView> rows, int limit) {

        boolean hasMore = rows.size() > limit;

        List<EntityView> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);

        String nextCursor = hasMore && !items.isEmpty()
                ? items.get(items.size() - 1).entityId().toString()
                : null;

        return new EntityPage(items, nextCursor);
    }

    private static EntityView mapFull(ResultSet rs, int rowNumber) throws SQLException {

        return new EntityView(
                rs.getObject("entity_id", UUID.class),
                rs.getString("entity_code"),
                rs.getString("entity_type"),
                rs.getString("legal_name_en"),
                rs.getString("legal_name_si"),
                rs.getString("legal_name_ta"),
                rs.getString("registration_no"),
                rs.getString("vat_registration_no"),
                rs.getString("district"),
                rs.getInt("financial_year_start_month"),
                rs.getString("default_language"),
                rs.getObject("responsible_officer_user_id", UUID.class),
                rs.getObject("data_governance_signed_on", LocalDate.class),
                rs.getString("status"));
    }

    private static EntityView mapParty(ResultSet rs, int rowNumber) throws SQLException {

        return new EntityView(
                rs.getObject("entity_id", UUID.class),
                null,
                null,
                rs.getString("legal_name_en"),
                rs.getString("legal_name_si"),
                rs.getString("legal_name_ta"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
