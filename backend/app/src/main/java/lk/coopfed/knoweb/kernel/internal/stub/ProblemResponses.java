package lk.coopfed.knoweb.kernel.internal.stub;

import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link ProblemException} into an RFC 9457 problem document (17A section 4.4).
 * The error code is the message id: a stable string the web client can test for, and the
 * key of the translated text in i18n/{en,si,ta}.json.
 *
 * <pre>
 *   { "status": 422, "code": "hello.greeting.duplicate",
 *     "title": "This greeting is already registered", "params": { ... } }
 * </pre>
 *
 * A broken business rule is 422. The few codes below are about the request itself, not
 * about a business rule, and are 400 (request.invalid also lists the fields: see
 * RequestValidationHandler). Used by the controller advices and by the idempotency
 * filter, which runs before Spring MVC and so cannot rely on the advice.
 */
@Component
public class ProblemResponses {

    private static final Map<String, HttpStatus> REQUEST_ERRORS = Map.ofEntries(
            Map.entry("scope.required", HttpStatus.BAD_REQUEST),
            Map.entry("scope.invalid", HttpStatus.BAD_REQUEST),
            Map.entry("idempotency.key_required", HttpStatus.BAD_REQUEST),
            Map.entry("request.invalid", HttpStatus.BAD_REQUEST),
            Map.entry("request.malformed", HttpStatus.BAD_REQUEST),
            // K-03b: a permission the role lacks is 403, not a broken business rule.
            Map.entry("permission.denied", HttpStatus.FORBIDDEN),
            // M2-02: a SKU the caller cannot see, on a read or a command, is 404 as the slice says.
            Map.entry("m2.sku.not_found", HttpStatus.NOT_FOUND),
            // K-02: a token the resource server refuses, and a second factor that is not fresh
            // enough for the action (19A section 2: step-up is 401 with the provider's address).
            Map.entry("token.invalid", HttpStatus.UNAUTHORIZED),
            Map.entry("auth.required", HttpStatus.UNAUTHORIZED),
            Map.entry("mfa.required", HttpStatus.UNAUTHORIZED),
            // K-08: the sync contract's own answers (doc 32 sections 3.2, 3.3, 7 and 9). The
            // device is refused 403 (suspended: with the signed revoke instruction), a batch that
            // does not follow the cursor or meets another in flight is 409, an old application
            // is 426, a batch over the limits 413.
            Map.entry("sync.device_token_required", HttpStatus.FORBIDDEN),
            Map.entry("sync.device_token_not_allowed", HttpStatus.FORBIDDEN),
            Map.entry("sync.device_mismatch", HttpStatus.FORBIDDEN),
            Map.entry("sync.location_mismatch", HttpStatus.FORBIDDEN),
            Map.entry("sync.device_unknown", HttpStatus.FORBIDDEN),
            Map.entry("sync.device_not_active", HttpStatus.FORBIDDEN),
            Map.entry("sync.device_not_enrolled", HttpStatus.FORBIDDEN),
            Map.entry("sync.device_suspended", HttpStatus.FORBIDDEN),
            Map.entry("sync.device_retired", HttpStatus.FORBIDDEN),
            Map.entry("sync.enrolment.code_invalid", HttpStatus.FORBIDDEN),
            Map.entry("sync.sequence_gap", HttpStatus.CONFLICT),
            Map.entry("sync.batch_in_flight", HttpStatus.CONFLICT),
            Map.entry("sync.batch_inconsistent", HttpStatus.BAD_REQUEST),
            Map.entry("sync.batch_too_large", HttpStatus.PAYLOAD_TOO_LARGE),
            Map.entry("sync.app_below_floor", HttpStatus.UPGRADE_REQUIRED),
            Map.entry("sync.snapshot.unavailable", HttpStatus.NOT_IMPLEMENTED));

    private final Messages messages;

    public ProblemResponses(Messages messages) {
        this.messages = messages;
    }

    public ProblemDetail toProblem(ProblemException e, Locale locale) {
        HttpStatus status = REQUEST_ERRORS.getOrDefault(e.messageId(), HttpStatus.UNPROCESSABLE_ENTITY);

        ProblemDetail problem = ProblemDetail.forStatus(status);
        Messages.Text title = messages.text(e.messageId(), locale);
        problem.setTitle(title.value());
        if (title.fallback()) {
            // Shown in English because the language lacks the text: the client marks it (doc 19 section 5.1).
            problem.setProperty("fallback", true);
        }
        problem.setProperty("code", e.messageId());
        problem.setProperty("params", e.parameters());
        return problem;
    }
}
