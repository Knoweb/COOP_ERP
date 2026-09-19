package lk.coopfed.knoweb.kernel.internal.stub;

import jakarta.servlet.http.HttpServletRequest;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One place where a {@link ProblemException} thrown by any handler or controller becomes
 * an HTTP response. Module code throws the exception with a message id and never builds
 * an error response itself.
 */
@RestControllerAdvice
public class ProblemExceptionHandler {

    private final ProblemResponses problems;

    public ProblemExceptionHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ProblemException.class)
    public ProblemDetail handle(ProblemException e, HttpServletRequest request) {
        return problems.toProblem(e, request.getLocale());
    }
}
