package lk.coopfed.knoweb.m3pricing.internal.label;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.query.LabelJobView;
import lk.coopfed.knoweb.m3pricing.query.LabelRefusal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads label jobs under RLS and serialises their small refusal result. Writes stay in handlers. */
@Component
public class LabelJobStore {

    static final String QUEUED = "QUEUED";
    static final String RENDERED = "RENDERED";
    static final String FAILED = "FAILED";

    private static final String COLUMNS = "job_id, owner_entity_id, location_id, template, sku_ids, refused,"
            + " object_key, status, created_by, created_at";
    private static final TypeReference<List<LabelRefusal>> REFUSALS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    LabelJobStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Row> find(UUID jobId) {
        return jdbc.query("select " + COLUMNS + " from pricing.label_job where job_id = ?", this::row, jobId).stream()
                .findFirst();
    }

    Optional<Row> findForUpdate(UUID jobId) {
        return jdbc
                .query("select " + COLUMNS + " from pricing.label_job where job_id = ? for update", this::row, jobId)
                .stream()
                .findFirst();
    }

    /** Every queued job, oldest first, read through the job's federation-wide scope. */
    @Transactional(readOnly = true)
    public List<Row> queued(ScopeContext scope) {
        return jdbc.query(
                "select " + COLUMNS + " from pricing.label_job where status = 'QUEUED'"
                        + " order by created_at, job_id",
                this::row);
    }

    public LabelJobView view(Row row) {
        int rendered = RENDERED.equals(row.status())
                ? row.skuIds().size() - row.refused().size()
                : 0;
        return new LabelJobView(
                row.jobId(),
                row.locationId(),
                row.status(),
                row.skuIds().size(),
                rendered,
                row.refused(),
                row.objectKey(),
                row.createdAt());
    }

    String refusedJson(List<LabelRefusal> refused) {
        try {
            return mapper.writeValueAsString(refused);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise label refusals", e);
        }
    }

    private Row row(ResultSet rs, int index) throws SQLException {
        return new Row(
                rs.getObject("job_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getString("template"),
                uuids(rs.getArray("sku_ids")),
                refusals(rs.getString("refused")),
                rs.getString("object_key"),
                rs.getString("status"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private List<LabelRefusal> refusals(String json) {
        try {
            return mapper.readValue(json, REFUSALS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read label refusals", e);
        }
    }

    private static List<UUID> uuids(Array value) throws SQLException {
        return List.copyOf(Arrays.asList((UUID[]) value.getArray()));
    }

    public record Row(
            UUID jobId,
            UUID ownerEntityId,
            UUID locationId,
            String template,
            List<UUID> skuIds,
            List<LabelRefusal> refused,
            String objectKey,
            String status,
            UUID createdBy,
            java.time.Instant createdAt) {}
}
