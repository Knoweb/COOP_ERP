package lk.coopfed.knoweb.m2catalogue.internal.sku;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "sku", schema = "catalogue")
class Sku implements Persistable<UUID> {

    static final String DRAFT = "DRAFT";
    static final String LOCAL = "LOCAL";
    static final String SHARED = "SHARED";
    static final String INACTIVE = "INACTIVE";

    @Id
    @Column(name = "sku_id", nullable = false)
    private UUID id;

    @Column(name = "sku_code", nullable = false, length = 12, unique = true)
    private String skuCode;

    @Column(name = "owner_entity_id", nullable = false)
    private UUID ownerEntityId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "prior_status")
    private String priorStatus;

    @Column(name = "short_name_en", nullable = false, length = 80)
    private String shortNameEn;

    @Column(name = "short_name_si", length = 80)
    private String shortNameSi;

    @Column(name = "short_name_ta", length = 80)
    private String shortNameTa;

    @Column(name = "description_en")
    private String descriptionEn;

    @Column(name = "description_si")
    private String descriptionSi;

    @Column(name = "description_ta")
    private String descriptionTa;

    @Column(name = "base_uom_code", nullable = false, length = 10)
    private String baseUomCode;

    @Column(name = "sold_by_weight", nullable = false)
    private boolean soldByWeight;

    @Column(name = "batch_tracked", nullable = false)
    private boolean batchTracked;

    @Column(name = "expiry_tracked", nullable = false)
    private boolean expiryTracked;

    @Column(name = "has_printed_mrp", nullable = false)
    private boolean hasPrintedMrp;

    @Column(name = "expiry_warning_days")
    private Short expiryWarningDays;

    @Column(name = "tax_category_id", nullable = false)
    private UUID taxCategoryId;

    @Column(name = "multi_mrp_policy", nullable = false)
    private String multiMrpPolicy;

    @Column(name = "origin_kind", nullable = false)
    private String originKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new LinkedHashMap<>();

    @Transient
    private boolean isNew = true;

    protected Sku() {}

    static Sku create(UUID id, String code, UUID ownerEntityId, SkuDetails details) {
        if (id == null || ownerEntityId == null) {
            throw new ProblemException("request.invalid");
        }

        Sku sku = new Sku();
        sku.id = id;
        sku.skuCode = required(code, "skuCode");
        sku.ownerEntityId = ownerEntityId;
        sku.status = DRAFT;
        sku.priorStatus = null;
        sku.apply(details);
        return sku;
    }

    void update(SkuDetails details) {
        if (INACTIVE.equals(status)) {
            throw new ProblemException("m2.sku.inactive");
        }
        apply(details);
    }

    void activateLocal() {
        requireStatus(DRAFT);
        status = LOCAL;
        priorStatus = null;
    }

    void activateShared() {
        requireStatus(DRAFT);

        if (shortNameSi == null || shortNameTa == null) {
            throw new ProblemException("m2.sku.shared_translations_required");
        }

        status = SHARED;
        priorStatus = null;
    }

    void deactivate() {
        if (!LOCAL.equals(status) && !SHARED.equals(status)) {
            throw new ProblemException("m2.sku.transition_invalid");
        }

        priorStatus = status;
        status = INACTIVE;
    }

    void reactivate() {
        requireStatus(INACTIVE);

        if (!LOCAL.equals(priorStatus) && !SHARED.equals(priorStatus)) {
            throw new ProblemException("m2.sku.prior_status_missing");
        }

        status = priorStatus;
        priorStatus = null;
    }

    private void apply(SkuDetails details) {
        if (details == null) {
            throw new ProblemException("request.invalid");
        }

        shortNameEn = required(details.shortNameEn(), "shortNameEn");
        shortNameSi = blankToNull(details.shortNameSi());
        shortNameTa = blankToNull(details.shortNameTa());

        descriptionEn = blankToNull(details.descriptionEn());
        descriptionSi = blankToNull(details.descriptionSi());
        descriptionTa = blankToNull(details.descriptionTa());

        baseUomCode = required(details.baseUomCode(), "baseUomCode").toUpperCase();

        soldByWeight = details.soldByWeight();
        batchTracked = details.batchTracked();
        expiryTracked = details.expiryTracked();
        hasPrintedMrp = details.hasPrintedMrp();
        expiryWarningDays = details.expiryWarningDays();

        if (details.taxCategoryId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "taxCategoryId"));
        }
        taxCategoryId = details.taxCategoryId();

        multiMrpPolicy =
                details.multiMrpPolicy() == null || details.multiMrpPolicy().isBlank()
                        ? "AUTO_LOWEST"
                        : details.multiMrpPolicy().strip().toUpperCase();

        originKind = details.originKind() == null || details.originKind().isBlank()
                ? "PURCHASED"
                : details.originKind().strip().toUpperCase();

        attributes = details.attributes() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details.attributes());

        if (soldByWeight && !"KG".equals(baseUomCode)) {
            throw new ProblemException("m2.sku.weight_requires_kg");
        }

        if (expiryTracked && !batchTracked) {
            throw new ProblemException("m2.sku.expiry_requires_batch");
        }
    }

    private void requireStatus(String expected) {
        if (!expected.equals(status)) {
            throw new ProblemException("m2.sku.transition_invalid");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", field));
        }
        return value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    UUID ownerEntityId() {
        return ownerEntityId;
    }

    String skuCode() {
        return skuCode;
    }

    String status() {
        return status;
    }

    String priorStatus() {
        return priorStatus;
    }

    String baseUomCode() {
        return baseUomCode;
    }

    boolean batchTracked() {
        return batchTracked;
    }

    boolean expiryTracked() {
        return expiryTracked;
    }

    UUID taxCategoryId() {
        return taxCategoryId;
    }

    Map<String, Object> auditState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("skuId", id);
        state.put("skuCode", skuCode);
        state.put("ownerEntityId", ownerEntityId);
        state.put("status", status);
        state.put("priorStatus", priorStatus);
        state.put("shortNameEn", shortNameEn);
        state.put("shortNameSi", shortNameSi);
        state.put("shortNameTa", shortNameTa);
        state.put("baseUomCode", baseUomCode);
        state.put("soldByWeight", soldByWeight);
        state.put("batchTracked", batchTracked);
        state.put("expiryTracked", expiryTracked);
        state.put("hasPrintedMrp", hasPrintedMrp);
        state.put("expiryWarningDays", expiryWarningDays);
        state.put("taxCategoryId", taxCategoryId);
        state.put("multiMrpPolicy", multiMrpPolicy);
        state.put("originKind", originKind);
        state.put("attributes", new LinkedHashMap<>(attributes));
        return Collections.unmodifiableMap(state);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }
}
