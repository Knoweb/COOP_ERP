package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitBrokerAdapterTest {

    private RabbitTemplate rabbit;
    private RabbitBrokerAdapter adapter;

    @BeforeEach
    void setUp() {

        rabbit = mock(RabbitTemplate.class);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        adapter = new RabbitBrokerAdapter(rabbit, mapper);

        doAnswer(invocation -> {
                    CorrelationData correlation = invocation.getArgument(3);

                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));

                    return null;
                })
                .when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void domainEventsUseTheDomainExchangeAndEventTypeRoutingKey() {

        OutboxMessage event = message();

        adapter.publish(event);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);

        verify(rabbit)
                .send(
                        eq(RabbitBrokerAdapter.DOMAIN_EXCHANGE),
                        eq(event.eventType()),
                        sent.capture(),
                        any(CorrelationData.class));

        assertThat(sent.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);

        assertThat(sent.getValue().getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
    }

    @Test
    void retryIsSentDirectlyToTheConsumerQueueWithAttemptHeader() {

        OutboxMessage event = message();

        adapter.publishToConsumer("m5.receipts", event, 2);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);

        verify(rabbit).send(eq(""), eq("m5.receipts"), sent.capture(), any(CorrelationData.class));

        Object attempt = sent.getValue().getMessageProperties().getHeader("x-attempt");

        assertThat(attempt).isEqualTo(2);
    }

    @Test
    void anUnroutableMessageIsAFailureAlthoughTheBrokerConfirmsIt() {

        // RabbitMQ hands a mandatory message back when no queue is bound for it, then confirms it:
        // it reached nobody, so the relay must not mark it published (review wave 3, KRN-23).
        doAnswer(invocation -> {
                    Message sent = invocation.getArgument(2);
                    CorrelationData correlation = invocation.getArgument(3);

                    correlation.setReturned(new ReturnedMessage(
                            sent, 312, "NO_ROUTE", invocation.getArgument(0), invocation.getArgument(1)));
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));

                    return null;
                })
                .when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        OutboxMessage event = message();

        assertThatThrownBy(() -> adapter.publish(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(event.eventId().toString())
                .hasMessageContaining("NO_ROUTE");
    }

    private static OutboxMessage message() {

        UUID aggregateId = UUID.randomUUID();

        return new OutboxMessage(
                UUID.randomUUID(),
                "hello.greeting.registered.v1",
                Instant.now(),
                "central",
                42L,
                UUID.randomUUID(),
                null,
                "greeting",
                aggregateId,
                UUID.randomUUID(),
                null,
                null,
                null,
                "{\"greetingId\":\"" + aggregateId + "\"}");
    }
}
