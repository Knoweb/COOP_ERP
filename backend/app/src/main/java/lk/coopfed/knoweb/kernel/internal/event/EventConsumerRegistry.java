package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
public class EventConsumerRegistry implements BeanPostProcessor {

    private final Map<Key, Registration> registrations = new ConcurrentHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {

        register(bean);
        return bean;
    }

    void register(Object bean) {

        Class<?> type = AopUtils.getTargetClass(bean);

        for (Method method : type.getDeclaredMethods()) {

            EventConsumer annotation = method.getAnnotation(EventConsumer.class);

            if (annotation == null) {
                continue;
            }

            validate(method, annotation);

            Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());

            invocable.setAccessible(true);

            for (String eventType : annotation.types()) {

                Key key = new Key(annotation.consumer(), eventType);

                Registration existing = registrations.putIfAbsent(
                        key,
                        new Registration(
                                annotation.consumer(), eventType, bean, invocable, method.getParameterTypes()[0]));

                if (existing != null) {
                    throw new IllegalStateException(
                            "Duplicate event consumer registration for " + annotation.consumer() + " / " + eventType);
                }
            }
        }
    }

    Optional<Registration> find(String consumer, String eventType) {

        return Optional.ofNullable(registrations.get(new Key(consumer, eventType)));
    }

    Map<String, java.util.Set<String>> bindings() {

        Map<String, java.util.Set<String>> result = new java.util.TreeMap<>();

        for (Key key : registrations.keySet()) {

            result.computeIfAbsent(key.consumer(), ignored -> new java.util.TreeSet<>())
                    .add(key.eventType());
        }

        return result;
    }

    private static void validate(Method method, EventConsumer annotation) {

        if (annotation.consumer() == null || annotation.consumer().isBlank()) {

            throw new IllegalStateException("@EventConsumer consumer must not be blank: " + method);
        }

        if (annotation.types().length == 0) {
            throw new IllegalStateException("@EventConsumer needs at least one event type: " + method);
        }

        Class<?>[] parameters = method.getParameterTypes();

        if (parameters.length != 2 || !ScopeContext.class.equals(parameters[1])) {

            throw new IllegalStateException(
                    "@EventConsumer method must take " + "(DomainEvent, ScopeContext): " + method);
        }

        if (!DomainEvent.class.isAssignableFrom(parameters[0]) && !JsonNode.class.isAssignableFrom(parameters[0])) {

            throw new IllegalStateException(
                    "@EventConsumer first parameter must be " + "a DomainEvent subtype or JsonNode: " + method);
        }
    }

    private record Key(String consumer, String eventType) {}

    record Registration(String consumer, String eventType, Object bean, Method method, Class<?> payloadType) {

        void invoke(String payload, ScopeContext scope, ObjectMapper mapper) {

            try {

                Object event = JsonNode.class.isAssignableFrom(payloadType)
                        ? mapper.readTree(payload)
                        : mapper.readValue(payload, payloadType);

                method.invoke(bean, event, scope);

            } catch (InvocationTargetException e) {

                Throwable cause = e.getCause();

                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }

                throw new IllegalStateException("Event consumer failed", cause);

            } catch (ReflectiveOperationException | java.io.IOException e) {

                throw new IllegalStateException("Cannot invoke event consumer " + consumer + " for " + eventType, e);
            }
        }
    }
}
