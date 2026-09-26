package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The per-instance cache fan-out (19A section 3: the permission cache is "invalidated by
 * role.changed.v1, role.assigned/revoked.v1, user.deactivated.v1 fanned out over the broker";
 * doc 19 section 8 says the same of config.changed.v1). Runs in every role, because every
 * instance holds the caches: one exclusive, auto-deleted queue of this instance on the domain
 * exchange, bound to the event types the {@link CacheFanoutListener}s name, so that each
 * instance receives its own copy of every such event. The worker's consumer queues and the
 * inbox ({@link RabbitConsumerRuntime}) deliver an event to one instance once, which is right
 * for a handler and wrong for a cache; this path bypasses them.
 *
 * <p>Nothing here is a fact that must not be lost: a delivery is acknowledged as it arrives,
 * a message that cannot be read is dropped with a log line, and a listener that fails does not
 * stop the others. An instance that cannot reach the broker serves its caches to their expiry
 * and tries again at the next check; a queue the broker forgot (the connection dropped, the
 * exclusive queue went with it) is declared afresh then.
 *
 * <p>RabbitMQ-specific on purpose, like the consumer runtime beside it; {@link BrokerAdapter}
 * stays the neutral seam the publishing side goes through.
 */
@Component
@ConditionalOnProperty(name = "coop-erp.rabbit.cache-fanout.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitCacheFanoutRuntime {

    private static final Logger log = LoggerFactory.getLogger(RabbitCacheFanoutRuntime.class);

    private final ConnectionFactory connectionFactory;
    private final ObjectProvider<CacheFanoutListener> listeners;
    private final ObjectMapper mapper;

    private SimpleMessageListenerContainer container;

    public RabbitCacheFanoutRuntime(
            ConnectionFactory connectionFactory, ObjectProvider<CacheFanoutListener> listeners, ObjectMapper mapper) {
        this.connectionFactory = connectionFactory;
        this.listeners = listeners;
        this.mapper = mapper;
    }

    @Scheduled(
            initialDelayString = "${coop-erp.rabbit.consumer-start-delay-ms:1000}",
            fixedDelayString = "${coop-erp.rabbit.consumer-start-check-ms:60000}")
    public synchronized void start() {

        if (container != null && container.isRunning()) {
            return;
        }
        if (container != null) {
            // The container gave up (the broker went away and the exclusive queue with it):
            // a fresh queue on the next connection.
            container.stop();
            container = null;
        }

        TreeSet<String> types = eventTypes();

        if (types.isEmpty()) {
            return;
        }

        try {
            RabbitAdmin admin = new RabbitAdmin(connectionFactory);

            TopicExchange exchange = new TopicExchange(RabbitBrokerAdapter.DOMAIN_EXCHANGE, true, false);

            admin.declareExchange(exchange);

            Queue queue = fanoutQueue();

            admin.declareQueue(queue);

            for (String eventType : types) {
                admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(eventType));
            }

            SimpleMessageListenerContainer fresh = new SimpleMessageListenerContainer(connectionFactory);

            fresh.setQueueNames(queue.getName());
            fresh.setAcknowledgeMode(AcknowledgeMode.AUTO);
            fresh.setConcurrentConsumers(1);
            fresh.setMaxConcurrentConsumers(1);
            fresh.setDefaultRequeueRejected(false);
            fresh.setMessageListener(this::consume);

            fresh.start();

            container = fresh;

            log.info("Cache fan-out queue {} bound to {}", queue.getName(), types);

        } catch (RuntimeException brokerAway) {

            // Not an error of this instance: the caches expire on their own, and the next check
            // tries again.
            log.warn(
                    "Cache fan-out queue not declared ({}); the caches serve to their expiry until the broker answers",
                    String.valueOf(brokerAway.getMessage()));
        }
    }

    /**
     * This instance's queue: server-named by intent (an anonymous queue), exclusive to this
     * connection, deleted with it, never durable. Nothing in it survives the instance, and nothing
     * should: what it carries is "empty your cache", which the expiry does anyway.
     */
    static Queue fanoutQueue() {
        return new AnonymousQueue();
    }

    /** The union of what the listeners bind to, in a fixed order for the log line. */
    TreeSet<String> eventTypes() {
        TreeSet<String> types = new TreeSet<>();
        listeners.forEach(listener -> types.addAll(listener.eventTypes()));
        return types;
    }

    /** One delivery: the listeners of its type, each on its own; nothing is ever requeued. */
    void consume(Message message) {

        String eventType;
        JsonNode payload;

        try {
            OutboxMessage event = mapper.readValue(message.getBody(), OutboxMessage.class);
            eventType = event.eventType();
            payload = event.payload() == null ? mapper.createObjectNode() : mapper.readTree(event.payload());
        } catch (java.io.IOException | RuntimeException unreadable) {
            log.warn("Cache fan-out dropped a message it cannot read ({})", String.valueOf(unreadable.getMessage()));
            return;
        }

        List<CacheFanoutListener> interested = listeners.stream()
                .filter(listener -> listener.eventTypes().contains(eventType))
                .toList();

        for (CacheFanoutListener listener : interested) {
            try {
                listener.published(eventType, payload);
            } catch (RuntimeException failure) {
                log.warn(
                        "Cache fan-out listener {} failed on {}",
                        listener.getClass().getName(),
                        eventType,
                        failure);
            }
        }
    }

    @PreDestroy
    public synchronized void stop() {

        if (container != null) {
            container.stop();
            container = null;
        }
    }
}
