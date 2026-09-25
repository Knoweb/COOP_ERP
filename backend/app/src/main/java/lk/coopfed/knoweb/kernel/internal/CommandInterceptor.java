package lk.coopfed.knoweb.kernel.internal;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Order(TransactionOrderConfig.COMMAND_INTERCEPTOR_ORDER)
public class CommandInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CommandInterceptor.class);

    private static final int COMMAND_SUCCESS = 200;

    /** How fresh a second factor must be for a permission that asks for one (doc 19 DR-7 refines it). */
    static final Duration MFA_FRESHNESS = Duration.ofMinutes(10);

    private final IdempotencyStore idempotency;
    private final ObjectMapper mapper;
    private final PermissionResolver permissions;
    private final Clock clock;
    private final boolean enforcePermissions;
    private final String stepUpUrl;

    public CommandInterceptor(
            IdempotencyStore idempotency,
            ObjectMapper mapper,
            PermissionResolver permissions,
            Clock clock,
            @Value("${coop-erp.security.enforce-permissions:false}") boolean enforcePermissions,
            @Value("${coop-erp.security.oidc.step-up-url:}") String stepUpUrl) {
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.permissions = permissions;
        this.clock = clock;
        this.enforcePermissions = enforcePermissions;
        this.stepUpUrl = stepUpUrl;
        if (!enforcePermissions) {
            log.warn("Permissions are resolved but NOT enforced (coop-erp.security.enforce-permissions=false):"
                    + " switch it on where the token's user holds roles (compose: COOP_ERP_ENFORCE_PERMISSIONS)");
        }
    }

    @Around("@within(lk.coopfed.knoweb.kernel.api.CommandHandler)")
    public Object intercept(ProceedingJoinPoint call) throws Throwable {

        RequestData request = currentRequest();

        if (request == null) {
            // K-03a covers commands that arrive over HTTP. A command from a sync batch, a job or a
            // consumer has no request and is NOT protected yet; said out loud here so that the gap
            // is visible in the log and not discovered by a double-applied fact. K-08 (sync) and
            // K-12 (jobs) give those callers their own keys. K-03b adds the permission and MFA
            // checks before this point (19A section 3: permission -> MFA -> idempotency -> handler).
            log.warn(
                    "{} ran outside an HTTP request: no idempotency check (K-03a covers HTTP only)",
                    call.getSignature().getDeclaringType().getSimpleName());
            return call.proceed();
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {

            throw new IllegalStateException("CommandInterceptor ran outside the handler transaction");
        }

        ScopeContext scope = findScope(call.getArgs());

        if (scope == null) {
            throw new IllegalStateException("A command handler takes the ScopeContext of the request");
        }
        if (scope.userId() == null) {
            // No token and no development user header: nobody to run the command as, nobody to
            // attribute it to, and no idempotency key that could be a user's (K-03a).
            throw new ProblemException("scope.required");
        }

        // 19A section 3, in this order: permission -> MFA -> idempotency -> handler.
        checkPermission(call, scope);

        IdempotencyStore.Key key = new IdempotencyStore.Key(request.key(), scope.userId(), request.requestHash());

        IdempotencyStore.Claim claim = idempotency.claim(key);

        if (claim instanceof IdempotencyStore.Replay replay) {
            return deserialize(call, replay.result().body());
        }

        Object result = call.proceed();

        String resultBody = mapper.writeValueAsString(result);

        idempotency.complete(key, new IdempotencyStore.StoredResult(COMMAND_SUCCESS, resultBody));

        return result;
    }

    /**
     * The handler's permission (its @CommandHandler) against the caller's roles in the scope,
     * then the second factor where the catalogue asks for one. Refuses only when enforcement is
     * on; until then a would-be refusal is logged, so the gap between the roles and the handlers
     * shows before K-02 makes it bite.
     */
    private void checkPermission(ProceedingJoinPoint call, ScopeContext scope) {
        Class<?> type = call.getSignature().getDeclaringType();
        CommandHandler handler = type.getAnnotation(CommandHandler.class);
        String permission = handler == null ? null : handler.permission();
        if (permission == null || permission.isBlank()) {
            return;
        }

        boolean allowed = permissions.allows(scope, permission);
        boolean mfaFresh = !permissions.requiresMfa(permission)
                || (scope.mfaAt() != null
                        && !scope.mfaAt().isBefore(clock.instant().minus(MFA_FRESHNESS)));

        if (!enforcePermissions) {
            if (!allowed || !mfaFresh) {
                log.info(
                        "Would refuse {} for user {} at entity {}: allowed={}, mfaFresh={} (not enforced)",
                        permission,
                        scope.userId(),
                        scope.entityId(),
                        allowed,
                        mfaFresh);
            }
            return;
        }
        if (!allowed) {
            throw new ProblemException("permission.denied", Map.of("permission", permission));
        }
        if (!mfaFresh) {
            // 19A section 2: 401 with the provider's step-up address; the web shell takes the
            // user there and retries the command with the fresher token (17A section 7).
            throw new ProblemException("mfa.required", Map.of("permission", permission, "stepUpUrl", stepUpUrl));
        }
    }

    private Object deserialize(ProceedingJoinPoint call, String body) throws Exception {

        MethodSignature signature = (MethodSignature) call.getSignature();

        if (signature.getReturnType() == Void.TYPE) {
            return null;
        }

        JavaType type =
                mapper.getTypeFactory().constructType(signature.getMethod().getGenericReturnType());

        return mapper.readValue(body, type);
    }

    private static ScopeContext findScope(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ScopeContext scope) {
                return scope;
            }
        }

        return null;
    }

    private static RequestData currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }

        Object key = attributes.getRequest().getAttribute(IdempotencyRequestAttributes.KEY);

        Object hash = attributes.getRequest().getAttribute(IdempotencyRequestAttributes.REQUEST_HASH);

        if (!(key instanceof String keyText) || !(hash instanceof String hashText)) {
            return null;
        }

        return new RequestData(keyText, hashText);
    }

    private record RequestData(String key, String requestHash) {}
}
