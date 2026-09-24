package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@ConditionalOnBean(RabbitBrokerAdapter.class)
public class RabbitConsumerRuntime {

    private final ConnectionFactory connectionFactory;
    private final EventConsumerRegistry registry;
    private final EventConsumerDispatcher dispatcher;
    private final RabbitBrokerAdapter broker;
    private final ObjectMapper mapper;

    private final List<SimpleMessageListenerContainer> containers = new ArrayList<>();

    public RabbitConsumerRuntime(
            ConnectionFactory connectionFactory,
            EventConsumerRegistry registry,
            EventConsumerDispatcher dispatcher,
            RabbitBrokerAdapter broker,
            ObjectMapper mapper) {

        this.connectionFactory = connectionFactory;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.broker = broker;
        this.mapper = mapper;
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

            Queue queue = new Queue(
                    consumer, true, false, false, Map.of("x-queue-type", "quorum", "x-single-active-consumer", true));

            admin.declareQueue(queue);

            for (String eventType : binding.getValue()) {

                admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(eventType));
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

    private void consume(String consumer, Message message, Channel channel) throws Exception {

        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {

            OutboxMessage event = mapper.readValue(message.getBody(), OutboxMessage.class);

            int attempt = attemptOf(message);

            EventConsumerDispatcher.DeliveryResult result = dispatcher.deliver(consumer, event, attempt);

            if (result == EventConsumerDispatcher.DeliveryResult.RETRY) {

                broker.publishToConsumer(consumer, event, attempt + 1);
            }

            channel.basicAck(deliveryTag, false);

        } catch (Exception failure) {

            channel.basicNack(deliveryTag, false, true);

            throw failure;
        }
    }

    private static int attemptOf(Message message) {

        Object raw = message.getMessageProperties().getHeaders().get("x-attempt");

        if (raw instanceof Number number) {
            return Math.max(1, number.intValue());
        }

        if (raw != null) {

            try {
                return Math.max(1, Integer.parseInt(raw.toString()));
            } catch (NumberFormatException ignored) {
                // Fall through.
            }
        }

        return 1;
    }

    @PreDestroy
    public synchronized void stop() {

        for (SimpleMessageListenerContainer container : containers) {

            container.stop();
        }

        containers.clear();
    }
}
