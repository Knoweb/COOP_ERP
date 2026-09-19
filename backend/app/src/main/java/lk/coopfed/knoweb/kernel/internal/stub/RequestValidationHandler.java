package lk.coopfed.knoweb.kernel.internal.stub;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One place where a request that does not match its OpenAPI slice becomes an HTTP response.
 *
 * <p>The slice says what a request looks like (required, minLength, maximum, format ...). The
 * generator writes that as constraint annotations on the generated interface and classes, and
 * Spring checks them before the controller runs. Spring's own answer is an English sentence in
 * its own shape; this class answers like everything else in the API: a problem document whose
 * codes are message ids, translated into the caller's language (AGENTS.md).
 *
 * <pre>
 *   { "status": 400, "code": "request.invalid", "title": "...",
 *     "errors": [ { "field": "textEn", "code": "request.field.required",
 *                   "message": "This is required", "params": {} } ] }
 * </pre>
 *
 * <p>A module writes no validation code and no message for these: the ids are generic
 * (request.field.*). What stays in the handler, as a guard, is every business rule, and every
 * rule that must also hold for a command that did not arrive over HTTP (till sync, jobs).
 */
@RestControllerAdvice
public class RequestValidationHandler {

    private static final String INVALID = "request.invalid";
    private static final String MALFORMED = "request.malformed";

    private final ProblemResponses problems;
    private final Messages messages;

    public RequestValidationHandler(ProblemResponses problems, Messages messages) {
        this.problems = problems;
        this.messages = messages;
    }

    /** A request body that breaks its schema: {@code @Valid @RequestBody}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail body(MethodArgumentNotValidException e, HttpServletRequest request) {
        Locale locale = request.getLocale();
        return invalid(locale, e.getBindingResult().getFieldErrors().stream()
                .map(error -> entry(error.getField(), error, error.getRejectedValue(), locale))
                .toList());
    }

    /**
     * A header, query parameter or path variable that breaks its constraints. The generated
     * interfaces are {@code @Validated}, so these are checked around the controller method and
     * arrive as the validator's own exception.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail parameters(ConstraintViolationException e, HttpServletRequest request) {
        Locale locale = request.getLocale();
        return invalid(locale, e.getConstraintViolations().stream()
                .map(violation -> entry(nameOf(violation), violation, locale))
                .toList());
    }

    /** The same, for a controller that is not {@code @Validated}: Spring MVC checks it itself. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail parameters(HandlerMethodValidationException e, HttpServletRequest request) {
        Locale locale = request.getLocale();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ParameterValidationResult result : e.getAllValidationResults()) {
            if (result instanceof ParameterErrors body) {
                body.getFieldErrors().forEach(error ->
                        errors.add(entry(error.getField(), error, error.getRejectedValue(), locale)));
            } else {
                String name = nameOf(result.getMethodParameter());
                result.getResolvableErrors().forEach(error ->
                        errors.add(entry(name, error, result.getArgument(), locale)));
            }
        }
        return invalid(locale, errors);
    }

    /** A value of the wrong type where no body is involved: /greetings/not-a-uuid. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail wrongType(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        Locale locale = request.getLocale();
        return invalid(locale, List.of(entry(e.getName(), "request.field.invalid", Map.of(), locale)));
    }

    /** A body that cannot be read at all: broken JSON, text where a number belongs, no body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return problems.toProblem(new ProblemException(MALFORMED), request.getLocale());
    }

    private ProblemDetail invalid(Locale locale, List<Map<String, Object>> errors) {
        ProblemDetail problem = problems.toProblem(new ProblemException(INVALID), locale);
        problem.setProperty("errors", errors.stream()
                .sorted(Comparator.comparing(error -> String.valueOf(error.get("field"))))
                .toList());
        return problem;
    }

    /**
     * Spring wraps the validator's finding. Usually the constraint is inside. For a plain method
     * parameter Spring 6.1 keeps only its own description: the constraint's name as the last
     * code, and as arguments the parameter, then the attribute values in the alphabetical order
     * of their names (Size: max, min; DecimalMin: inclusive, value).
     */
    private Map<String, Object> entry(String field, MessageSourceResolvable error, Object rejected, Locale locale) {
        if (error instanceof ObjectError wrapped && wrapped.contains(ConstraintViolation.class)) {
            return entry(field, wrapped.unwrap(ConstraintViolation.class), locale);
        }
        String[] codes = error.getCodes();
        Object[] arguments = error.getArguments();
        if (codes == null || codes.length == 0 || arguments == null) {
            return entry(field, "request.field.invalid", Map.of(), locale);
        }
        String constraint = codes[codes.length - 1];
        Map<String, Object> attributes = switch (constraint) {
            case "Size" -> Map.of("max", arguments[1], "min", arguments[2]);
            case "Min", "Max" -> Map.of("value", arguments[1]);
            case "DecimalMin", "DecimalMax" -> Map.of("value", arguments[2]);
            default -> Map.of();
        };
        return entry(field, constraint, attributes, rejected, locale);
    }

    private Map<String, Object> entry(String field, ConstraintViolation<?> violation, Locale locale) {
        ConstraintDescriptor<?> constraint = violation.getConstraintDescriptor();
        return entry(
                field,
                constraint.getAnnotation().annotationType().getSimpleName(),
                constraint.getAttributes(),
                violation.getInvalidValue(),
                locale);
    }

    /** From a constraint to a message id. The slice's keywords arrive as these annotations. */
    private Map<String, Object> entry(
            String field, String constraint, Map<String, Object> attributes, Object rejected, Locale locale) {
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> entry(field, "request.field.required", Map.of(), locale);
            case "Size" -> {
                int size = sizeOf(rejected);
                yield size >= 0 && size < (int) attributes.get("min")
                        ? entry(field, "request.field.too_short", Map.of("min", attributes.get("min")), locale)
                        : entry(field, "request.field.too_long", Map.of("max", attributes.get("max")), locale);
            }
            case "Min", "DecimalMin" ->
                    entry(field, "request.field.too_small", Map.of("min", attributes.get("value")), locale);
            case "Max", "DecimalMax" ->
                    entry(field, "request.field.too_large", Map.of("max", attributes.get("value")), locale);
            case "Pattern", "Email" -> entry(field, "request.field.format", Map.of(), locale);
            default -> entry(field, "request.field.invalid", Map.of(), locale);
        };
    }

    private Map<String, Object> entry(String field, String code, Map<String, Object> params, Locale locale) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("code", code);
        entry.put("message", messages.t(code, locale, params.values().toArray()));
        entry.put("params", params);
        return entry;
    }

    /** The last step of the path: registerGreeting.idempotencyKey -> idempotencyKey. */
    private static String nameOf(ConstraintViolation<?> violation) {
        String name = "";
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name;
    }

    /** The name the caller knows the parameter by: the header or query name, not the Java name. */
    private static String nameOf(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null && !query.value().isEmpty()) {
            return query.value();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.value().isEmpty()) {
            return path.value();
        }
        return parameter.getParameterName();
    }

    private static int sizeOf(Object value) {
        if (value instanceof CharSequence text) {
            return text.length();
        }
        if (value instanceof Collection<?> items) {
            return items.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return value != null && value.getClass().isArray() ? Array.getLength(value) : -1;
    }
}
