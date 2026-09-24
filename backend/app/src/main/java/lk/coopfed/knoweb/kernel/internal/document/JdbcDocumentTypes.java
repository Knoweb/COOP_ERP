package lk.coopfed.knoweb.kernel.internal.document;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.DocumentType;
import lk.coopfed.knoweb.kernel.api.DocumentTypes;
import lk.coopfed.knoweb.kernel.api.SeriesScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** The registry as the application user reads it: reference data, visible to every class. */
@Component
class JdbcDocumentTypes implements DocumentTypes {

    private static final String COLUMNS = "doc_type_code, name_en, name_si, name_ta, series_scope, issuer_role,"
            + " bilateral, fiscal, offline_issuable, owning_module";

    static final RowMapper<DocumentType> ROW = (ResultSet rs, int rowNum) -> new DocumentType(
            rs.getString("doc_type_code"),
            rs.getString("name_en"),
            rs.getString("name_si"),
            rs.getString("name_ta"),
            SeriesScope.valueOf(rs.getString("series_scope")),
            rs.getString("issuer_role"),
            rs.getBoolean("bilateral"),
            rs.getBoolean("fiscal"),
            rs.getBoolean("offline_issuable"),
            rs.getString("owning_module"));

    private final JdbcTemplate jdbc;

    JdbcDocumentTypes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DocumentType> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbc
                .query("select " + COLUMNS + " from kernel.document_type where doc_type_code = ?", ROW, code)
                .stream()
                .findFirst();
    }

    @Override
    public List<DocumentType> all() {
        return jdbc.query("select " + COLUMNS + " from kernel.document_type order by doc_type_code", ROW);
    }
}
