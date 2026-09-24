package lk.coopfed.knoweb.kernel.internal.document;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SeriesScope;
import org.springframework.jdbc.core.RowMapper;

/** One row of {@code kernel.numbering_series}, as the kernel reads it. */
record Series(
        UUID seriesId,
        String docTypeCode,
        SeriesScope scope,
        UUID ownerEntityId,
        UUID locationId,
        UUID tillPositionId,
        String prefix,
        long nextNumber,
        UUID holderDeviceId,
        String status) {

    static final String SELECT = "select series_id, doc_type_code, series_scope, owner_entity_id, location_id,"
            + " till_position_id, prefix, next_number, holder_device_id, status from kernel.numbering_series";

    static final RowMapper<Series> ROW = (rs, rowNum) -> new Series(
            rs.getObject("series_id", UUID.class),
            rs.getString("doc_type_code"),
            SeriesScope.valueOf(rs.getString("series_scope")),
            rs.getObject("owner_entity_id", UUID.class),
            rs.getObject("location_id", UUID.class),
            rs.getObject("till_position_id", UUID.class),
            rs.getString("prefix"),
            rs.getLong("next_number"),
            rs.getObject("holder_device_id", UUID.class),
            rs.getString("status"));
}
