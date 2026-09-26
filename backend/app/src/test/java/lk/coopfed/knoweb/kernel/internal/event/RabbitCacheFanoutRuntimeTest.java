package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The per-instance cache fan-out: an exclusive queue of this instance's own, and a delivery
 * handed to every cache listening for its type, with nothing requeued and no inbox in the way
 * (a cache lives on every instance, so "once" is wrong for it). The broker itself is not
 * started: there is no RabbitMQ container in testsupport, so the queue's shape is proved by its
 * declaration and the delivery with a message built by hand, as {@link RabbitConsumerRuntimeTest}
 * does.
 */
class RabbitCacheFanoutRuntimeTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void theQueueIsThisInstancesAloneAndDiesWithIt() {
        Queue queue = RabbitCacheFanoutRuntime.fanoutQueue();

        assertThat(queue.isExclusive()).isTrue();
        assertThat(queue.isAutoDelete()).isTrue();
        assertThat(queue.isDurable()).isFalse();
        assertThat(queue.getName()).isNotBlank();
    }

    @Test
    void theRuntimeBindsTheUnionOfWhatTheCachesListenFor() {
        Heard permissions = new Heard(Set.of("role.changed.v1", "role.revoked.v1"));
        Heard config = new Heard(Set.of("config.changed.v1"));

        RabbitCacheFanoutRuntime runtime = runtime(permissions, config);

        assertThat(runtime.eventTypes()).containsExactly("config.changed.v1", "role.changed.v1", "role.revoked.v1");
    }

    @Test
    void aDeliveryReachesEveryCacheOfItsTypeAndNoOther() throws Exception {
        Heard permissions = new Heard(Set.of("role.changed.v1"));
        Heard config = new Heard(Set.of("config.changed.v1"));
        RabbitCacheFanoutRuntime runtime = runtime(permissions, config);

        runtime.consume(message(event("config.changed.v1", "{\"key\":\"sync.batch.max_bytes\"}")));

        assertThat(config.types).containsExactly("config.changed.v1");
        assertThat(config.payloads.getFirst().path("key").asText()).isEqualTo("sync.batch.max_bytes");
        assertThat(permissions.types).isEmpty();
    }

    @Test
    void theSameEventTwiceEmptiesTheCacheTwiceBecauseNoInboxIsInTheWay() throws Exception {
        Heard permissions = new Heard(Set.of("role.revoked.v1"));
        RabbitCacheFanoutRuntime runtime = runtime(permissions);
        OutboxMessage revoked = event("role.revoked.v1", "{\"userId\":\"" + UUID.randomUUID() + "\"}");

        runtime.consume(message(revoked));
        runtime.consume(message(revoked));

        assertThat(permissions.types).containsExactly("role.revoked.v1", "role.revoked.v1");
    }

    @Test
    void aFailingCacheDoesNotStopTheOthersAndAnUnreadableMessageIsDropped() throws Exception {
        Heard failing = new Heard(Set.of("role.changed.v1")) {
            @Override
            public void published(String eventType, JsonNode payload) {
                throw new IllegalStateException("cache away");
            }
        };
        Heard hearing = new Heard(Set.of("role.changed.v1"));
        RabbitCacheFanoutRuntime runtime = runtime(failing, hearing);

        runtime.consume(message(event("role.changed.v1", "{}")));
        runtime.consume(new Message("not json".getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        assertThat(hearing.types).containsExactly("role.changed.v1");
    }

    private RabbitCacheFanoutRuntime runtime(CacheFanoutListener... listeners) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        int i = 0;
        for (CacheFanoutListener listener : listeners) {
            beans.addBean("listener" + i++, listener);
        }
        return new RabbitCacheFanoutRuntime(
                org.mockito.Mockito.mock(ConnectionFactory.class),
                beans.getBeanProvider(CacheFanoutListener.class),
                mapper);
    }

    private Message message(OutboxMessage event) throws Exception {
        return new Message(mapper.writeValueAsBytes(event), new MessageProperties());
    }

    private static OutboxMessage event(String type, String payload) {
        return new OutboxMessage(
                UUID.randomUUID(),
                type,
                Instant.now(),
                "central",
                1L,
                UUID.randomUUID(),
                null,
                "thing",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                payload);
    }

    /** A cache that records what it heard. */
    private static class Heard implements CacheFanoutListener {

        final Set<String> listensFor;
        final List<String> types = new ArrayList<>();
        final List<JsonNode> payloads = new ArrayList<>();

        Heard(Set<String> listensFor) {
            this.listensFor = listensFor;
        }

        @Override
        public Set<String> eventTypes() {
            return listensFor;
        }

        @Override
        public void published(String eventType, JsonNode payload) {
            types.add(eventType);
            payloads.add(payload);
        }
    }
}
