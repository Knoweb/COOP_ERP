package lk.coopfed.knoweb.kernel.internal.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(BrokerAdapter.class)
public class DeadLetter {

    public static final String QUEUE = "domain.dlq";

    private final BrokerAdapter broker;

    public DeadLetter(BrokerAdapter broker) {
        this.broker = broker;
    }

    public void send(String consumer, OutboxMessage message, int attempts, String error) {

        broker.deadLetter(QUEUE, consumer, message, attempts, error);
    }
}
