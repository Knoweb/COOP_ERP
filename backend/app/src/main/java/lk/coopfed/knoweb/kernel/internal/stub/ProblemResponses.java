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

    private static final Map<String, HttpStatus> REQUEST_ERRORS = Map.of(
            "scope.required", HttpStatus.BAD_REQUEST,
            "scope.invalid", HttpStatus.BAD_REQUEST,
            "idempotency.key_required", HttpStatus.BAD_REQUEST,
            "request.invalid", HttpStatus.BAD_REQUEST,
            "request.malformed", HttpStatus.BAD_REQUEST,
            // K-03b: a permission the role lacks is 403, not a broken business rule.
            "permission.denied", HttpStatus.FORBIDDEN);

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
