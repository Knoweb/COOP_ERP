package lk.coopfed.knoweb.kernel.internal.job;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scope the platform itself acts in when no request is behind the work (19A section 12).
 * {@code coop-erp.system.entity-id} names the entity, the Federation; in its OWN scope a job
 * writes what it must (an audit record, a numbering gap finding). Empty when it is not
 * configured: the caller then logs and writes nothing.
 *
 * <p>{@link #inOwnScope} and {@link #inFederationView} open a transaction with the given scope
 * applied to the connection: they are public and {@code @Transactional} with a
 * {@link ScopeContext} argument, which is what the kernel's connection customizer looks for.
 */
@Component
public class SystemScope {

    /** A scope for reading every entity's rows (fed_view); the entity id on it is nobody's. */
    private static final UUID NOBODY = new UUID(0, 0);

    private final Optional<UUID> entityId;

    public SystemScope(@Value("${coop-erp.system.entity-id:}") String entityId) {
        this.entityId =
                entityId == null || entityId.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(entityId));
    }

    /** The OWN scope of the system entity, with a fresh correlation id, or empty when not configured. */
    public Optional<ScopeContext> own() {
        return entityId.map(entity -> own(entity, null));
    }

    /** The OWN scope of any entity, for work the platform does on that entity's behalf (a cut-off day close). */
    public static ScopeContext own(UUID entity, UUID locationId) {
        Scope active = new Scope(entity, locationId);
        return new ScopeContext(
                null,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
    }

    /** A read-only view over every entity, for a job that has to see the whole fleet. */
    public static ScopeContext federationView() {
        Scope active = new Scope(NOBODY, null);
        return new ScopeContext(
                null,
                null,
                NOBODY,
                List.of(active),
                active,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
    }

    @Transactional
    public <T> T inScope(ScopeContext scope, Supplier<T> work) {
        return work.get();
    }
}
