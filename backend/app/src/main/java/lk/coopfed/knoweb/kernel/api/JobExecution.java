package lk.coopfed.knoweb.kernel.api;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a {@link ScheduledJob} method may ask of the run it is in.
 */
public interface JobExecution {

    /** The id of this run's row in {@code kernel.job_run}. */
    UUID runId();

    /** Counts what the job processed, for the run history; the return value of the method does the same. */
    void itemsProcessed(int count);

    /**
     * The scope the platform itself acts in when no request is behind the work: the OWN
     * scope of the entity {@code coop-erp.system.entity-id} names (the Federation). Empty
     * when it is not configured; a job that must write tenant rows or an audit record then
     * logs instead.
     */
    Optional<ScopeContext> systemScope();

    /**
     * The OWN scope of any entity, for a job that settles rows each entity owns in that entity's
     * name (M2's thumbnail job settles an image of the entity that attached it; CR-19A-7). No
     * user: the command's permission is not checked, as for {@link #systemScope()}.
     */
    default ScopeContext ownScopeOf(UUID entityId) {
        if (entityId == null) {
            throw new IllegalArgumentException("An OWN scope needs an entity");
        }
        Scope active = new Scope(entityId, null);
        return new ScopeContext(
                null, null, entityId, List.of(active), active, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    /**
     * A read-only view over every entity's rows (the fed_view policies), for a job that must find
     * the work of the whole fleet before it acts on each row in its owner's scope. The entity on
     * it is nobody's, so an OWN policy admits nothing through it.
     */
    default ScopeContext federationView() {
        Scope active = new Scope(new UUID(0, 0), null);
        return new ScopeContext(
                null,
                null,
                active.entityId(),
                List.of(active),
                active,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }
}
