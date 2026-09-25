package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

final class SkuGuards {

    private SkuGuards() {}

    static void requireEntityWideScope(ScopeContext scope) {
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.locationId() != null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
    }

    static Sku requireOwned(SkuRepository repository, UUID skuId, ScopeContext scope) {

        requireEntityWideScope(scope);

        if (skuId == null) {
            throw new ProblemException("request.field.required", Map.of("field", "skuId"));
        }

        Sku sku = repository
                .findById(skuId)
                .orElseThrow(() -> new ProblemException("m2.sku.not_found", Map.of("skuId", skuId)));

        if (!scope.entityId().equals(sku.ownerEntityId())) {
            throw new ProblemException("m2.sku.owner_mismatch", Map.of("skuId", skuId));
        }

        return sku;
    }

    static void requireReason(String reasonCode, String reasonText) {
        boolean noCode = reasonCode == null || reasonCode.isBlank();
        boolean noText = reasonText == null || reasonText.isBlank();

        if (noCode && noText) {
            throw new ProblemException("m2.sku.reason_required");
        }
    }

    static String reason(String reasonCode, String reasonText) {
        requireReason(reasonCode, reasonText);

        String code = reasonCode == null ? null : reasonCode.strip();
        String text = reasonText == null ? null : reasonText.strip();

        if (code == null || code.isBlank()) {
            return text;
        }

        if (text == null || text.isBlank()) {
            return code;
        }

        return code + ": " + text;
    }
}
