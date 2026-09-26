package lk.coopfed.knoweb.kernel.internal;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.security.PermissionGate;
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

    private final IdempotencyStore idempotency;
    private final ObjectMapper mapper;
    private final PermissionGate gate;

    public CommandInterceptor(
            IdempotencyStore idempotency,
            ObjectMapper mapper,
            PermissionGate gate,
            @Value("${coop-erp.security.enforce-permissions:false}") boolean enforcePermissions) {
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.gate = gate;
        if (!enforcePermissions) {
            log.warn("Permissions are resolved but NOT enforced (coop-erp.security.enforce-permissions=false):"
                    + " switch it on where the token's user holds roles (compose: COOP_ERP_ENFORCE_PERMISSIONS)");
        }
    }

    @Around("@within(lk.coopfed.knoweb.kernel.api.CommandHandler)")
    public Object intercept(ProceedingJoinPoint call) throws Throwable {

        ScopeContext scope = findScope(call.getArgs());

        // 19A section 3, in this order: permission -> MFA -> idempotency -> handler. The permission
        // check comes first for every caller that names a user, over HTTP or not: a consumer or a
        // job that runs a command as a user is held to the user's roles like a request.
        if (scope != null && scope.userId() != null) {
            checkPermission(call, scope);
        }

        RequestData request = currentRequest();

        if (request == null) {
            // K-03a covers commands that arrive over HTTP. A command from a sync batch, a job or a
            // consumer has no request and no idempotency key of its own yet; said out loud here so
            // that the gap is visible in the log and not discovered by a double-applied fact. K-08
            // (sync) and K-12 (jobs) give those callers their own keys.
            log.warn(
                    "{} ran outside an HTTP request: no idempotency check (K-03a covers HTTP only)",
                    call.getSignature().getDeclaringType().getSimpleName());
            return call.proceed();
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {

            throw new IllegalStateException("CommandInterceptor ran outside the handler transaction");
        }

        if (scope == null) {
            throw new IllegalStateException("A command handler takes the ScopeContext of the request");
        }
        if (scope.userId() == null) {
            // A device or system token without a user: nobody to run the command as, nobody to
            // attribute it to, and no idempotency key that could be a user's (K-03a).
            throw new ProblemException("scope.required");
        }

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
     * The handler's permission (its @CommandHandler) through the one rule of the kernel
     * ({@link PermissionGate}: the roles in the scope, then the second factor where the
     * catalogue asks for one; refused only when enforcement is on). The kernel's own operations
     * (an enrolment code, K-08) go through the same gate, so one rule holds everywhere.
     */
    private void checkPermission(ProceedingJoinPoint call, ScopeContext scope) {
        Class<?> type = call.getSignature().getDeclaringType();
        CommandHandler handler = type.getAnnotation(CommandHandler.class);
        gate.require(scope, handler == null ? null : handler.permission());
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
