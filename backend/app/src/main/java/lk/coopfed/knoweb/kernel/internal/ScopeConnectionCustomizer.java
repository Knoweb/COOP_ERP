package lk.coopfed.knoweb.kernel.internal;

import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Aspect
@Component
@Order(TransactionOrderConfig.SCOPE_CUSTOMIZER_ORDER)
public class ScopeConnectionCustomizer {

    private static final String SET_SCOPE = "select "
            + "set_config('app.user_id', ?, true), "
            + "set_config('app.correlation_id', ?, true), "
            + "set_config('app.scope_entity_id', ?, true), "
            + "set_config('app.scope_location_id', ?, true), "
            + "set_config('app.scope_class', ?, true), "
            + "set_config('app.granted_entities', ?, true)";

    private final JdbcTemplate jdbc;

    public ScopeConnectionCustomizer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Around("execution(public * lk.coopfed.knoweb..*(..))"
            + " && (@annotation(org.springframework.transaction.annotation.Transactional)"
            + " || @within(org.springframework.transaction.annotation.Transactional))")
    public Object applyScope(ProceedingJoinPoint call) throws Throwable {

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ScopeConnectionCustomizer ran outside an active transaction");
        }

        ScopeContext scope = findScope(call.getArgs());

        String policyClass = sessionClass(scope);

        String userId = scope == null ? "" : text(scope.userId());

        String correlationId = scope == null ? "" : text(scope.correlationId());

        String entityId = PolicyClass.NONE.name().equals(policyClass) ? "" : text(scope.entityId());

        String locationId = PolicyClass.NONE.name().equals(policyClass) ? "" : text(scope.locationId());

        String grantedEntities =
                scope != null && PolicyClass.EXTERNAL_TIMEBOXED.name().equals(policyClass)
                        ? GrantedEntities.settingValue(scope.grantedEntities())
                        : "{}";

        jdbc.queryForList(SET_SCOPE, userId, correlationId, entityId, locationId, policyClass, grantedEntities);

        return call.proceed();
    }

    private static ScopeContext findScope(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ScopeContext scope) {
                return scope;
            }
        }

        return null;
    }

    private static String sessionClass(ScopeContext scope) {

        if (scope == null || !scope.hasActiveScope()) {
            return PolicyClass.NONE.name();
        }

        return switch (scope.policyClass()) {
            case API_CLIENT, DEVICE -> PolicyClass.OWN.name();
            case OWN, PARTY, FEDERATION_VIEW, EXTERNAL_TIMEBOXED ->
                scope.policyClass().name();
            case NONE -> PolicyClass.NONE.name();
        };
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
