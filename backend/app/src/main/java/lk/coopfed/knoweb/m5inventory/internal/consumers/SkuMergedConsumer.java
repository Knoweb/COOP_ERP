package lk.coopfed.knoweb.m5inventory.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * On sku.merged.v1, lots of the merged local SKU re-point to the target canonical SKU (doc 25 section 3.1; 22A section 6).
 */
@Component
public class SkuMergedConsumer {

    static final String CONSUMER = "m5.sku_merge";
    static final String SKU_MERGED = "sku.merged.v1";

    private final JdbcTemplate jdbc;

    public SkuMergedConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    @EventConsumer(types = SKU_MERGED, consumer = CONSUMER)
    public void onSkuMerged(JsonNode payload, ScopeContext scope) {
        UUID sourceSkuId = Payloads.uuid(payload, "sourceSkuId");
        UUID canonicalSkuId = Payloads.uuid(payload, "canonicalSkuId");

        if (sourceSkuId != null && canonicalSkuId != null) {
            jdbc.execute("SELECT set_config('app.fed_promote', 'true', true)");
            try {
                jdbc.update(
                        """
                        UPDATE inventory.stock_lot
                           SET sku_id = ?
                         WHERE sku_id = ?
                        """,
                        canonicalSkuId,
                        sourceSkuId);
            } finally {
                jdbc.execute("SELECT set_config('app.fed_promote', '', true)");
            }
        }
    }
}
