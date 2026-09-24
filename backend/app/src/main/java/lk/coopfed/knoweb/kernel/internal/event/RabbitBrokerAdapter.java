package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
public class RabbitBrokerAdapter implements BrokerAdapter {

    static final String DOMAIN_EXCHANGE = "domain";

    private static final int CONFIRM_TIMEOUT_SECONDS = 10;

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public RabbitBrokerAdapter(RabbitTemplate rabbit, ObjectMapper mapper) {

        this.rabbit = rabbit;
        this.mapper = mapper;

        this.rabbit.setMandatory(true);
    }

    @Override
    public void publish(OutboxMessage message) {

        sendConfirmed(DOMAIN_EXCHANGE, message.eventType(), message, 1, null, null);
    }

    @Override
    public void publishToConsumer(String consumer, OutboxMessage message, int attempt) {

        if (consumer == null || consumer.isBlank()) {
            throw new IllegalArgumentException("Consumer queue must not be blank");
        }

        sendConfirmed("", consumer, message, attempt, consumer, null);
    }

    @Override
    public void deadLetter(String queue, String consumer, OutboxMessage message, int attempts, String error) {

        sendConfirmed("", queue, message, attempts, consumer, error);
    }

    private void sendConfirmed(
            String exchange, String routingKey, OutboxMessage message, int attempt, String consumer, String error) {

        MessageProperties properties = new MessageProperties();

        properties.setContentType("application/json");

        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        properties.setMessageId(message.eventId().toString());

        properties.setType(message.eventType());

        properties.setHeader("x-attempt", attempt);

        properties.setHeader("x-source", message.source());

        properties.setHeader("x-source-seq", message.sourceSeq());

        if (consumer != null) {
            properties.setHeader("x-consumer", consumer);
        }

        if (error != null) {
            properties.setHeader("x-error", error);
        }

        Message outbound;

        try {

            outbound = new Message(mapper.writeValueAsBytes(message), properties);

        } catch (JsonProcessingException e) {

            throw new IllegalStateException("Cannot serialise outbox event " + message.eventId(), e);
        }

        CorrelationData correlation = new CorrelationData(message.eventId() + ":" + routingKey + ":" + attempt);

        rabbit.send(exchange, routingKey, outbound, correlation);

        try {

            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!confirm.isAck()) {

                throw new IllegalStateException(
                        "RabbitMQ rejected event " + message.eventId() + ": " + confirm.getReason());
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException("Interrupted while waiting for RabbitMQ confirm", e);

        } catch (ExecutionException | TimeoutException e) {

            throw new IllegalStateException("RabbitMQ did not confirm event " + message.eventId(), e);
        }
    }
}
