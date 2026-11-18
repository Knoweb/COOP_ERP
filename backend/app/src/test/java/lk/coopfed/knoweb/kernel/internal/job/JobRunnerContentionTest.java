package lk.coopfed.knoweb.kernel.internal.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.jdbc.core.JdbcTemplate;

class JobRunnerContentionTest {

    private final JobRegistry registry = new JobRegistry(new StandardEnvironment());
    private final LockProvider locks = mock(LockProvider.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final SystemScope system = mock(SystemScope.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneId.of("UTC"));

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ConfigRegistry> config = mock(ObjectProvider.class);

    private final SimpleLock mockLock = mock(SimpleLock.class);

    private JobRunner runner;

    @BeforeEach
    void setUp() {
        registry.register(new DummyJobs());
        runner = new JobRunner(registry, locks, jdbc, audit, system, clock, config);
        when(jdbc.queryForObject(any(String.class), any(Class.class), any())).thenReturn(Boolean.TRUE);
    }

    @Test
    void retriesTransientSerializationFailureAndSucceedsWhenNextAttemptSecuresLock() {
        when(locks.lock(any(LockConfiguration.class)))
                .thenThrow(
                        new CannotSerializeTransactionException("could not serialize access due to concurrent update"))
                .thenReturn(Optional.of(mockLock));

        JobRunner.Outcome outcome = runner.run("dummy-job");

        assertThat(outcome).isEqualTo(JobRunner.Outcome.SUCCESS);
        verify(locks, times(2)).lock(any(LockConfiguration.class));
        verify(mockLock).unlock();
    }

    @Test
    void treatsExhaustedContentionAsLockedByPeer() {
        when(locks.lock(any(LockConfiguration.class)))
                .thenThrow(
                        new CannotSerializeTransactionException("could not serialize access due to concurrent update"));

        JobRunner.Outcome outcome = runner.run("dummy-job");

        assertThat(outcome).isEqualTo(JobRunner.Outcome.SKIPPED_LOCKED);
        verify(locks, times(3)).lock(any(LockConfiguration.class));
    }

    @Test
    void treatsSqlState40001AsSerializationContention() {
        SQLException sqlEx = new SQLException("could not serialize access", "40001");
        when(locks.lock(any(LockConfiguration.class))).thenThrow(new RuntimeException("wrapped DB error", sqlEx));

        JobRunner.Outcome outcome = runner.run("dummy-job");

        assertThat(outcome).isEqualTo(JobRunner.Outcome.SKIPPED_LOCKED);
        verify(locks, times(3)).lock(any(LockConfiguration.class));
    }

    @Test
    void nonTransientExceptionIsPropagated() {
        when(locks.lock(any(LockConfiguration.class)))
                .thenThrow(new IllegalStateException("unexpected database crash"));

        assertThatThrownBy(() -> runner.run("dummy-job"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unexpected database crash");
    }

    static class DummyJobs {
        @ScheduledJob(name = "dummy-job", continuous = true, fixedDelay = "PT1M")
        public void execute() {}
    }
}
