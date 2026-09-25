package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

/**
 * What the consumer runtime does with each outcome of a delivery (#87, #88). The broker itself
 * is not started: there is no RabbitMQ container in testsupport yet, so the dead-letter routing
 * of the queue arguments is proved by the arguments, and the broker test is deferred.
 */
class RabbitConsumerRuntimeTest {

    private static final long TAG = 7L;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private EventConsumerDispatcher dispatcher;
    private DeadLetter deadLetter;
    private Channel channel;
    private RabbitConsumerRuntime runtime;

    @BeforeEach
    void setUp() {

        dispatcher = mock(EventConsumerDispatcher.class);
        deadLetter = mock(DeadLetter.class);
        channel = mock(Channel.class);

        runtime = new RabbitConsumerRuntime(
                mock(ConnectionFactory.class), new EventConsumerRegistry(), dispatcher, deadLetter, mapper, 0);
    }

    @Test
    void consumerQueuesDeadLetterToTheDlqWithADeliveryLimit() {

        assertThat(RabbitConsumerRuntime.consumerQueueArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-single-active-consumer", true)
                .containsEntry("x-dead-letter-exchange", "")
                .containsEntry("x-dead-letter-routing-key", DeadLetter.QUEUE)
                .containsEntry("x-delivery-limit", RabbitConsumerRuntime.DELIVERY_LIMIT);
    }

    @Test
    void aRetryIsRequeuedInPlaceNotRepublishedToTheTail() throws Exception {

        when(dispatcher.deliver(eq("c"), any(), eq(1))).thenReturn(EventConsumerDispatcher.DeliveryResult.RETRY);

        runtime.consume("c", message(event(), 0), channel);

        verify(channel).basicNack(TAG, false, true);
        verify(channel, never()).basicAck(TAG, false);
    }

    @Test
    void theRedeliveryCountOfTheQuorumQueueIsTheAttempt() throws Exception {

        when(dispatcher.deliver(eq("c"), any(), eq(3)))
                .thenReturn(EventConsumerDispatcher.DeliveryResult.DEAD_LETTERED);

        runtime.consume("c", message(event(), 2), channel);

        verify(dispatcher).deliver(eq("c"), any(), eq(3));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void aPoisonMessageGoesToTheDlqWithItsReasonAndIsAcknowledged() throws Exception {

        when(dispatcher.deliver(eq("c"), any(), anyInt())).thenThrow(new PoisonMessageException("nobody consumes it"));

        runtime.consume("c", message(event(), 0), channel);

        verify(deadLetter).send(eq("c"), any(), eq(1), eq("nobody consumes it"));
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(true));
    }

    @Test
    void aPoisonMessageIsRejectedToTheDeadLetterExchangeWhenTheSendFails() throws Exception {

        when(dispatcher.deliver(eq("c"), any(), anyInt())).thenThrow(new PoisonMessageException("nobody consumes it"));
        doThrow(new IllegalStateException("broker away"))
                .when(deadLetter)
                .send(anyString(), any(), anyInt(), anyString());

        runtime.consume("c", message(event(), 0), channel);

        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void anUnreadableBodyIsRejectedWithoutRequeue() throws Exception {

        runtime.consume("c", new Message("not json".getBytes(StandardCharsets.UTF_8), properties(0)), channel);

        verify(channel).basicNack(TAG, false, false);
        verify(dispatcher, never()).deliver(anyString(), any(), anyInt());
    }

    @Test
    void anyOtherFailureIsRequeuedInPlaceForTheDeliveryLimitToCatch() throws Exception {

        when(dispatcher.deliver(eq("c"), any(), anyInt())).thenThrow(new IllegalStateException("database away"));

        runtime.consume("c", message(event(), 4), channel);

        verify(channel).basicNack(TAG, false, true);
        verify(deadLetter, never()).send(anyString(), any(), anyInt(), anyString());
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private Message message(OutboxMessage event, int deliveryCount) throws Exception {

        return new Message(mapper.writeValueAsBytes(event), properties(deliveryCount));
    }

    private static MessageProperties properties(int deliveryCount) {

        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        properties.setHeader("x-attempt", 1);
        if (deliveryCount > 0) {
            properties.setHeader("x-delivery-count", deliveryCount);
        }
        return properties;
    }

    private static OutboxMessage event() {

        return new OutboxMessage(
                UUID.randomUUID(),
                "hello.greeting.registered.v1",
                Instant.now(),
                "central",
                1L,
                UUID.randomUUID(),
                null,
                "greeting",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                "{}");
    }
}
