package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@ConditionalOnBean(RabbitBrokerAdapter.class)
public class RabbitConsumerRuntime {

    private static final Logger log = LoggerFactory.getLogger(RabbitConsumerRuntime.class);

    private final ConnectionFactory connectionFactory;
    private final EventConsumerRegistry registry;
    private final EventConsumerDispatcher dispatcher;
    private final DeadLetter deadLetter;
    private final ObjectMapper mapper;
    private final long baseBackoffMs;

    private final List<SimpleMessageListenerContainer> containers = new ArrayList<>();

    public RabbitConsumerRuntime(
            ConnectionFactory connectionFactory,
            EventConsumerRegistry registry,
            EventConsumerDispatcher dispatcher,
            DeadLetter deadLetter,
            ObjectMapper mapper,
            @Value("${coop-erp.rabbit.retry-backoff-ms:200}") long baseBackoffMs) {

        this.connectionFactory = connectionFactory;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.deadLetter = deadLetter;
        this.mapper = mapper;
        this.baseBackoffMs = baseBackoffMs;
    }

    @Scheduled(
            initialDelayString = "${coop-erp.rabbit.consumer-start-delay-ms:1000}",
            fixedDelayString = "${coop-erp.rabbit.consumer-start-check-ms:60000}")
    public synchronized void start() {

        if (!containers.isEmpty()) {
            return;
        }

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);

        TopicExchange exchange = new TopicExchange(RabbitBrokerAdapter.DOMAIN_EXCHANGE, true, false);

        admin.declareExchange(exchange);

        Queue deadLetter = new Queue(DeadLetter.QUEUE, true, false, false, Map.of("x-queue-type", "quorum"));

        admin.declareQueue(deadLetter);

        for (Map.Entry<String, java.util.Set<String>> binding :
                registry.bindings().entrySet()) {

            String consumer = binding.getKey();

            Queue queue = new Queue(consumer, true, false, false, consumerQueueArguments());

            admin.declareQueue(queue);

            for (String eventType : binding.getValue()) {

                // "*" is every type: the topic exchange spells that "#".
                admin.declareBinding(
                        BindingBuilder.bind(queue).to(exchange).with("*".equals(eventType) ? "#" : eventType));
            }

            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);

            container.setQueueNames(consumer);

            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);

            container.setConcurrentConsumers(1);
            container.setMaxConcurrentConsumers(1);
            container.setPrefetchCount(1);
            container.setDefaultRequeueRejected(false);

            container.setMessageListener(
                    (ChannelAwareMessageListener) (message, channel) -> consume(consumer, message, channel));

            container.start();

            containers.add(container);
        }
    }

    /**
     * A consumer queue: quorum, one active consumer (per-source order), and a dead-letter route to
     * {@code domain.dlq} through the default exchange with a delivery limit, so a message that
     * keeps failing below the dispatcher (the database away, the audit refusing) ends in the dead
     * letters instead of blocking the queue for ever.
     *
     * <p>RabbitMQ refuses to redeclare an existing queue with other arguments
     * (PRECONDITION_FAILED): a stack whose queues were declared before these arguments needs a
     * fresh broker ({@code make reset} on compose).
     */
    static Map<String, Object> consumerQueueArguments() {

        return Map.of(
                "x-queue-type",
                "quorum",
                "x-single-active-consumer",
                true,
                "x-dead-letter-exchange",
                "",
                "x-dead-letter-routing-key",
                DeadLetter.QUEUE,
                "x-delivery-limit",
                DELIVERY_LIMIT);
    }

    /** Redeliveries the broker allows before it dead-letters a message on its own. */
    static final int DELIVERY_LIMIT = 10;

    private static final long MAX_BACKOFF_MS = 5_000;

    void consume(String consumer, Message message, Channel channel) throws Exception {

        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        int attempt = attemptOf(message);

        OutboxMessage event = null;

        try {

            event = mapper.readValue(message.getBody(), OutboxMessage.class);

            EventConsumerDispatcher.DeliveryResult result = dispatcher.deliver(consumer, event, attempt);

            if (result == EventConsumerDispatcher.DeliveryResult.RETRY) {

                // Retried in place: the message goes back to the head of the queue, and with one
                // active consumer and a prefetch of one nothing behind it overtakes it. Republished
                // to the tail it would come after later events of the same source.
                backoff(attempt);

                channel.basicNack(deliveryTag, false, true);

                return;
            }

            channel.basicAck(deliveryTag, false);

        } catch (JsonProcessingException | PoisonMessageException poison) {

            // A message that fails the same way on every delivery: requeued, it would come
            // straight back for ever and block the queue. It goes to domain.dlq with the reason;
            // a person decides. Handler failures never reach here: the dispatcher retries them
            // and dead-letters after three attempts.
            log.error(
                    "Consumer {} refuses a message it can never apply ({}); dead-lettered",
                    consumer,
                    poison.getMessage());

            deadLetterPoison(consumer, message, event, poison, channel, deliveryTag);

        } catch (Exception failure) {

            // Everything else (the database away, the audit refusing) may pass: requeue in place
            // after a backoff that grows with the attempt. The queue's delivery limit dead-letters
            // the message if it never does.
            log.warn("Consumer {} failed on attempt {}; requeued", consumer, attempt, failure);

            backoff(attempt);

            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void deadLetterPoison(
            String consumer, Message message, OutboxMessage event, Exception poison, Channel channel, long deliveryTag)
            throws Exception {

        if (event != null) {

            try {

                deadLetter.send(consumer, event, attemptOf(message), String.valueOf(poison.getMessage()));

                channel.basicAck(deliveryTag, false);

                return;

            } catch (RuntimeException sendFailed) {

                log.error("Consumer {} could not send a poison message to {}", consumer, DeadLetter.QUEUE, sendFailed);
            }
        }

        // A body that cannot be read, or a send that failed: the queue's dead-letter exchange
        // routes the rejected message to domain.dlq as it is.
        channel.basicNack(deliveryTag, false, false);
    }

    private void backoff(int attempt) throws InterruptedException {

        long delay = Math.min(MAX_BACKOFF_MS, baseBackoffMs << Math.min(attempt - 1, 10));

        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    /**
     * The attempt of this delivery: the x-attempt header a replay or an old republish set, plus
     * the redeliveries the quorum queue counted (x-delivery-count, set on a requeued message).
     */
    static int attemptOf(Message message) {

        Map<String, Object> headers = message.getMessageProperties().getHeaders();

        return Math.max(1, number(headers.get("x-attempt"), 1))
                + Math.max(0, number(headers.get("x-delivery-count"), 0));
    }

    private static int number(Object raw, int fallback) {

        if (raw instanceof Number number) {
            return number.intValue();
        }

        if (raw != null) {

            try {
                return Integer.parseInt(raw.toString());
            } catch (NumberFormatException ignored) {
                // Fall through.
            }
        }

        return fallback;
    }

    @PreDestroy
    public synchronized void stop() {

        for (SimpleMessageListenerContainer container : containers) {

            container.stop();
        }

        containers.clear();
    }
}
