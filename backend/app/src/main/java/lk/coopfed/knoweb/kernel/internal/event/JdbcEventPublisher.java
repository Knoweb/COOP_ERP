package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEventPublisher implements EventPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcEventPublisher(JdbcTemplate jdbc, ObjectMapper mapper) {

        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void publish(DomainEvent event) {

        if (event == null) {
            throw new IllegalArgumentException("Domain event must not be null");
        }

        String eventType = eventType(event);

        jdbc.update(
                """
                insert into kernel.outbox_event (
                    event_id,
                    event_type,
                    payload,
                    created_at
                )
                values (
                    ?,
                    ?,
                    cast(? as jsonb),
                    now()
                )
                """,
                UUID.randomUUID(),
                eventType,
                json(event));
    }

    private static String eventType(DomainEvent event) {

        try {
            Field field = event.getClass().getField("TYPE");

            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {

                throw new IllegalStateException(event.getClass().getName() + ".TYPE must be a static String");
            }

            String value = (String) field.get(null);

            if (value == null || value.isBlank() || !value.matches("[a-z0-9]+(?:\\.[a-z0-9]+)*\\.v[1-9][0-9]*")) {

                throw new IllegalStateException("Domain event TYPE is not versioned: " + value);
            }

            return value;

        } catch (ReflectiveOperationException ex) {

            throw new IllegalStateException(
                    "Domain event must expose public static TYPE: "
                            + event.getClass().getName(),
                    ex);
        }
    }

    private String json(Object event) {

        try {
            return mapper.writeValueAsString(event);

        } catch (JsonProcessingException ex) {

            throw new IllegalStateException(
                    "Unable to serialize domain event " + event.getClass().getName(), ex);
        }
    }
}
