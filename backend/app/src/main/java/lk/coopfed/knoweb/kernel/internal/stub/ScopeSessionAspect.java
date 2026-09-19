package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 17A stub of the 19A scope connection customizer (ticket K-01): puts the caller's scope
 * on the database transaction, where the row-level security policies read it through
 * kernel.scope_entity(), kernel.scope_location() and kernel.scope_class().
 *
 * <p>The rule for module code: <b>every {@code @Transactional} method that touches a table
 * takes the {@link ScopeContext} as a parameter.</b> This aspect finds that parameter and
 * sets the three session settings. A transactional method without one sets nothing, the
 * scope class stays NONE, and every policy returns no rows: it fails closed, exactly as
 * 19A section 1 requires of a transaction that forgets the customizer.
 *
 * <p>{@code set_config(..., true)} is SET LOCAL: the setting dies with the transaction.
 * That matters because PgBouncer pools per transaction, so the next transaction on the same
 * server connection may belong to another user.
 *
 * <p>Ordering: this aspect must run <i>inside</i> the transaction, so
 * {@link TransactionOrderConfig} gives the transaction interceptor a higher precedence
 * than the order below.
 */
@Aspect
@Component
@Order(TransactionOrderConfig.SCOPE_ASPECT_ORDER)
public class ScopeSessionAspect {

    private static final String SET_SCOPE =
            "select set_config('app.scope_entity_id', ?, true),"
                    + " set_config('app.scope_location_id', ?, true),"
                    + " set_config('app.scope_class', ?, true)";

    private final JdbcTemplate jdbc;

    public ScopeSessionAspect(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Around("execution(public * lk.coopfed.knoweb..*(..))"
            + " && (@annotation(org.springframework.transaction.annotation.Transactional)"
            + " || @within(org.springframework.transaction.annotation.Transactional))")
    public Object applyScope(ProceedingJoinPoint call) throws Throwable {
        ScopeContext scope = findScope(call.getArgs());

        if (scope != null) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException(
                        "ScopeSessionAspect ran outside a transaction; check TransactionOrderConfig");
            }
            jdbc.queryForList(
                    SET_SCOPE,
                    text(scope.entityId()),
                    text(scope.locationId()),
                    sessionClass(scope));
        }

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

    /**
     * The policies know OWN, PARTY, FEDERATION_VIEW and EXTERNAL_TIMEBOXED. The two
     * non-user principals act for their own entity (see {@link PolicyClass}); no active
     * scope means NONE, whatever the token said.
     */
    private static String sessionClass(ScopeContext scope) {
        if (!scope.hasActiveScope()) {
            return PolicyClass.NONE.name();
        }
        return switch (scope.policyClass()) {
            case API_CLIENT, DEVICE -> PolicyClass.OWN.name();
            default -> scope.policyClass().name();
        };
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
