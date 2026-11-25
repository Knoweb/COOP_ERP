package lk.coopfed.knoweb.m9integration.internal.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps database rows from integration.payment_intent to PaymentIntentView.
 */
public class PaymentRowMapper implements RowMapper<PaymentIntentView> {

    private final ObjectMapper objectMapper;

    public PaymentRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public PaymentIntentView mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID paymentId = rs.getObject("payment_id", UUID.class);
        UUID ownerEntityId = rs.getObject("owner_entity_id", UUID.class);
        String orderReference = rs.getString("order_reference");
        String customerRef = rs.getString("customer_ref");
        BigDecimal amount = rs.getBigDecimal("amount");
        String currency = rs.getString("currency");
        String paymentMethod = rs.getString("payment_method");
        String status = rs.getString("status");
        String provider = rs.getString("provider");
        String providerRef = rs.getString("provider_ref");
        String qrPayload = rs.getString("qr_payload");
        String description = rs.getString("description");
        String failureReason = rs.getString("failure_reason");
        String idempotencyKey = rs.getString("idempotency_key");
        String metadataJson = rs.getString("metadata");

        Map<String, Object> metadata = Map.of();
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                metadata = Map.of();
            }
        }

        Timestamp initTs = rs.getTimestamp("initiated_at");
        Instant initiatedAt = initTs != null ? initTs.toInstant() : null;

        Timestamp settleTs = rs.getTimestamp("settled_at");
        Instant settledAt = settleTs != null ? settleTs.toInstant() : null;

        Timestamp createTs = rs.getTimestamp("created_at");
        Instant createdAt = createTs != null ? createTs.toInstant() : null;

        Timestamp updateTs = rs.getTimestamp("updated_at");
        Instant updatedAt = updateTs != null ? updateTs.toInstant() : null;

        return new PaymentIntentView(
                paymentId,
                ownerEntityId,
                orderReference,
                customerRef,
                amount,
                currency,
                paymentMethod,
                status,
                provider,
                providerRef,
                qrPayload,
                description,
                failureReason,
                idempotencyKey,
                metadata,
                initiatedAt,
                settledAt,
                createdAt,
                updatedAt);
    }
}
