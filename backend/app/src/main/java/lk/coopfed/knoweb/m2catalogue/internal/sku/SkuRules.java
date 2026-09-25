package lk.coopfed.knoweb.m2catalogue.internal.sku;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import org.springframework.stereotype.Component;

@Component
class SkuRules {

    private static final int MAX_ATTRIBUTES_BYTES = 4096;

    private final SkuReferenceData references;
    private final ObjectMapper mapper;

    SkuRules(SkuReferenceData references, ObjectMapper mapper) {
        this.references = references;
        this.mapper = mapper;
    }

    void validate(SkuDetails details) {
        if (details == null) {
            throw new ProblemException("request.invalid");
        }

        if (details.shortNameEn() == null || details.shortNameEn().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "shortNameEn"));
        }

        if (details.baseUomCode() == null || details.baseUomCode().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "baseUomCode"));
        }

        String uom = details.baseUomCode().strip().toUpperCase();

        if (!references.uomExists(uom)) {
            throw new ProblemException("m2.sku.base_uom_unknown", Map.of("baseUomCode", uom));
        }

        if (details.taxCategoryId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "taxCategoryId"));
        }

        if (!references.taxCategoryExists(details.taxCategoryId())) {
            throw new ProblemException("m2.sku.tax_category_unknown", Map.of("taxCategoryId", details.taxCategoryId()));
        }

        if (details.soldByWeight() && !"KG".equals(uom)) {
            throw new ProblemException("m2.sku.weight_requires_kg");
        }

        if (details.expiryTracked() && !details.batchTracked()) {
            throw new ProblemException("m2.sku.expiry_requires_batch");
        }

        try {
            byte[] json = mapper.writeValueAsString(details.attributes() == null ? Map.of() : details.attributes())
                    .getBytes(StandardCharsets.UTF_8);

            if (json.length > MAX_ATTRIBUTES_BYTES) {
                throw new ProblemException("m2.sku.attributes_too_large");
            }
        } catch (JsonProcessingException e) {
            throw new ProblemException("request.invalid");
        }
    }
}
