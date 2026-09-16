package lk.coopfed.knoweb.kernel.api;

import java.util.Collections;
import java.util.Map;

public class ProblemException extends RuntimeException {

    private final String messageId;
    private final Map<String, Object> parameters;

    public ProblemException(String messageId) {
        this(messageId, Map.of());
    }

    public ProblemException(
            String messageId,
            Map<String, Object> parameters) {
        super(messageId);

        this.messageId = messageId;
        this.parameters = parameters == null
                ? Map.of()
                : Collections.unmodifiableMap(parameters);
    }

    public String messageId() {
        return messageId;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }
}