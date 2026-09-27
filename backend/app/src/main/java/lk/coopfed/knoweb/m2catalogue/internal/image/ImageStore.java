package lk.coopfed.knoweb.m2catalogue.internal.image;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of {@code catalogue.sku_image}, always under the caller's row-level security. The
 * handlers write; this class only reads.
 */
@Component
public class ImageStore {

    static final String PENDING = "PENDING";
    static final String ACTIVE = "ACTIVE";
    static final String RETIRED = "RETIRED";
    static final String FAILED = "FAILED";

    /** One row, as the handlers and the job need it. */
    public record ImageRow(
            UUID imageId,
            UUID skuId,
            String barcode,
            UUID ownerEntityId,
            String objectKeyFull,
            String objectKeyThumb,
            String contentHash,
            String contentType,
            String status,
            Instant uploadExpiresAt,
            Instant createdAt) {

        /** What the audit record keeps of the row: no URL, no bytes. */
        Map<String, Object> auditState() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("imageId", imageId);
            state.put("skuId", skuId);
            state.put("barcode", barcode);
            state.put("ownerEntityId", ownerEntityId);
            state.put("objectKeyFull", objectKeyFull);
            state.put("objectKeyThumb", objectKeyThumb);
            state.put("contentHash", contentHash);
            state.put("contentType", contentType);
            state.put("status", status);
            return state;
        }

        ImageRow withStatus(String newStatus, String thumbKey) {
            return new ImageRow(
                    imageId,
                    skuId,
                    barcode,
                    ownerEntityId,
                    objectKeyFull,
                    thumbKey,
                    contentHash,
                    contentType,
                    newStatus,
                    uploadExpiresAt,
                    createdAt);
        }
    }

    private static final String COLUMNS =
            """
            select image_id, sku_id, barcode, owner_entity_id, object_key_full, object_key_thumb,
                   content_hash, content_type, status, upload_expires_at, created_at
            from catalogue.sku_image
            """;

    private final JdbcTemplate jdbc;

    ImageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The caller's own row, locked for the command that changes it. */
    Optional<ImageRow> ownForUpdate(UUID imageId, ScopeContext scope) {
        return jdbc
                .query(
                        COLUMNS + " where image_id = ? and owner_entity_id = ? for update",
                        ImageStore::map,
                        imageId,
                        scope.entityId())
                .stream()
                .findFirst();
    }

    /** The caller's own row of that SKU, barcode and status, other than {@code except}. */
    Optional<ImageRow> ownWithStatus(UUID skuId, String barcode, String status, UUID except, ScopeContext scope) {
        return jdbc
                .query(
                        COLUMNS
                                + " where sku_id = ? and coalesce(barcode, '') = coalesce(?, '')"
                                + " and owner_entity_id = ? and status = ? and image_id <> ?"
                                + " for update",
                        ImageStore::map,
                        skuId,
                        barcode,
                        scope.entityId(),
                        status,
                        except == null ? new UUID(0, 0) : except)
                .stream()
                .findFirst();
    }

    /**
     * The PENDING images whose pre-signed PUT has expired, oldest first: the thumbnail job's work.
     * Public and {@code @Transactional} with the scope as an argument, so the kernel sets the
     * scope on the connection (the job reads with a federation-wide view).
     */
    @Transactional(readOnly = true)
    public List<ImageRow> dueForThumbnail(ScopeContext scope, Instant now, int limit) {
        return jdbc.query(
                COLUMNS + " where status = 'PENDING' and upload_expires_at <= ? order by upload_expires_at limit ?",
                ImageStore::map,
                Timestamp.from(now),
                limit);
    }

    private static ImageRow map(ResultSet rs, int row) throws SQLException {
        return new ImageRow(
                rs.getObject("image_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getString("barcode"),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("object_key_full"),
                rs.getString("object_key_thumb"),
                rs.getString("content_hash"),
                rs.getString("content_type"),
                rs.getString("status"),
                rs.getTimestamp("upload_expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
