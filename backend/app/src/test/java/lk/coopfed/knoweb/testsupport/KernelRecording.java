package lk.coopfed.knoweb.testsupport;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Puts the {@link KernelRecorder} behind the kernel's audit and event services in every
 * integration test, without naming either implementation.
 *
 * <p>Whatever bean implements {@link AuditFacade} or {@link EventPublisher} is wrapped, on its
 * way out of the factory, in a decorator that calls the real service first and then reports
 * what it was asked to do. The rest of the application injects the interface and gets the
 * decorator, so it sees the real behaviour, including every refusal the real service makes.
 *
 * <p>Why a wrapper and not a replacement: a test that asserts on a double proves what the
 * double did, not what the kernel does. Here the real service still runs, and the assertion
 * is about what it was given. And why a {@code BeanPostProcessor} and not a {@code @Primary}
 * stand-in: nothing here mentions {@code LoggingAuditFacade} or {@code LoggingEventPublisher},
 * so K-04 and K-05 replace them with the audit table and the outbox writer and no test
 * changes. A {@code @Primary} test bean would also have collided with a {@code @Primary} real
 * one.
 */
@Component
public class KernelRecording implements BeanPostProcessor {

    private final ObjectProvider<KernelRecorder> recorder;

    /**
     * A bean post-processor is created before almost everything else, so the recorder is
     * asked for lazily: taking it as a constructor argument would drag it, and anything it
     * touches, into that early phase.
     */
    public KernelRecording(ObjectProvider<KernelRecorder> recorder) {
        this.recorder = recorder;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        boolean audit = bean instanceof AuditFacade;
        boolean events = bean instanceof EventPublisher;

        if (audit && events) {
            throw new IllegalStateException(bean.getClass().getName()
                    + " implements both AuditFacade and EventPublisher. This class wraps one interface at a"
                    + " time, and a decorator for only one of them would take the other away from everything"
                    + " that injects it. Add a decorator here that implements both.");
        }
        if (audit) {
            return new RecordingAuditFacade((AuditFacade) bean, recorder);
        }
        if (events) {
            return new RecordingEventPublisher((EventPublisher) bean, recorder);
        }
        return bean;
    }

    /**
     * Implements {@link AuditFacade} rather than delegating every method, so that the
     * interface's shorter {@code record(...)} forms land on the method below and are recorded
     * too.
     */
    private record RecordingAuditFacade(AuditFacade target, ObjectProvider<KernelRecorder> recorder)
            implements AuditFacade {

        @Override
        public void record(
                String eventType,
                Subject subject,
                Object before,
                Object after,
                ScopeContext scope,
                String reason,
                UUID witnessUserId) {
            target.record(eventType, subject, before, after, scope, reason, witnessUserId);
            recorder.getObject()
                    .auditRecorded(new KernelRecorder.AuditRecord(
                            eventType, subject, before, after, scope, reason, witnessUserId));
        }
    }

    private record RecordingEventPublisher(EventPublisher target, ObjectProvider<KernelRecorder> recorder)
            implements EventPublisher {

        @Override
        public void publish(DomainEvent event) {
            KernelRecorder kernel = recorder.getObject();
            // Before the real publisher, so that the handler fails where the real outbox
            // insert would fail: the test then proves the whole transaction rolled back.
            kernel.failIfArmed();
            target.publish(event);
            kernel.eventPublished(event);
        }
    }
}
