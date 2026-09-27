package lk.coopfed.knoweb.m4trading.internal.document;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;

/** The guards the trading handlers share. */
public final class TradingGuards {

    private TradingGuards() {}

    /** An entity-wide OWN scope: orders, delivery notes and invoices are entity-level documents. */
    public static void requireEntityScope(ScopeContext scope) {
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.locationId() != null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
    }

    /** An OWN scope, entity-wide or at one location (a GRN is captured at a shop or entity-wide). */
    public static void requireOwnScope(ScopeContext scope) {
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
    }

    public static <T> T required(T value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new ProblemException("request.field.required", Map.of("field", field));
        }
        return value;
    }

    /** An item that can be traded: LOCAL or SHARED, not a draft and not deactivated (doc 22 section 3.1). */
    public static boolean tradable(SkuView sku) {
        return "LOCAL".equals(sku.status()) || "SHARED".equals(sku.status());
    }

    public static void requirePositive(BigDecimal qty, String messageId, UUID skuId) {
        if (qty == null || qty.signum() <= 0) {
            throw new ProblemException(messageId, Map.of("skuId", String.valueOf(skuId)));
        }
    }
}
