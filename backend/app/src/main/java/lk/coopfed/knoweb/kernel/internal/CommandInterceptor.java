package lk.coopfed.knoweb.kernel.internal;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public CommandInterceptor(IdempotencyStore idempotency, ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.mapper = mapper;
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

        if (!request.hasUser()) {
            throw new ProblemException("scope.required");
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {

            throw new IllegalStateException("CommandInterceptor ran outside the handler transaction");
        }

        ScopeContext scope = findScope(call.getArgs());

        if (scope == null || scope.userId() == null) {
            throw new IllegalStateException("HTTP commands need an authenticated user for idempotency");
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

        // Until K-02 reads the user from the token, the user is the X-Dev-User header, and a request
        // without one used to get a fresh random user, so a retry never matched and the command ran
        // twice with nobody the wiser. A mutating request without a user is refused instead.
        String user = attributes.getRequest().getHeader(DEV_USER_HEADER);

        return new RequestData(keyText, hashText, user != null && !user.isBlank());
    }

    private record RequestData(String key, String requestHash, boolean hasUser) {}

    /** The header the 17A development stub reads (DevCurrentScope.HEADER_USER); K-02 removes both. */
    private static final String DEV_USER_HEADER = "X-Dev-User";
}
