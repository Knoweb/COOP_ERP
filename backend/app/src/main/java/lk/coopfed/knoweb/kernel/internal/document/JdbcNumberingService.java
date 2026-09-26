package lk.coopfed.knoweb.kernel.internal.document;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DocumentType;
import lk.coopfed.knoweb.kernel.api.DocumentTypes;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesClosed;
import lk.coopfed.knoweb.kernel.api.SeriesHolderChanged;
import lk.coopfed.knoweb.kernel.api.SeriesRegistered;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.kernel.api.SeriesScope;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The counters, in {@code kernel.numbering_series} (19A section 7). Every method runs inside
 * the caller's transaction and scope: the row-level security of the table is what keeps an
 * entity to its own series, and the audit record and event go with the caller's commit.
 *
 * <p>{@link #takeNumber} is package-private on purpose: only the issuance protocol takes a
 * number, and only after the draft passed every check (doc 18 section 5.5, step 1).
 */
@Component
class JdbcNumberingService implements NumberingService {

    static final String AUDIT_REGISTERED = "SERIES_REGISTERED";
    static final String AUDIT_HOLDER_CHANGED = "SERIES_HOLDER_CHANGED";
    static final String AUDIT_CLOSED = "SERIES_CLOSED";

    /** The display number: {prefix}-{number:07d} (24B). */
    static final String NUMBER_FORMAT = "%s-%07d";

    private final JdbcTemplate jdbc;
    private final DocumentTypes types;
    private final AuditFacade audit;
    private final EventPublisher events;

    JdbcNumberingService(JdbcTemplate jdbc, DocumentTypes types, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.types = types;
        this.audit = audit;
        this.events = events;
    }

    @Override
    public UUID registerSeries(SeriesRegistration registration, ScopeContext ctx) {
        requireTransaction("registerSeries");

        DocumentType type = types.find(registration.docTypeCode())
                .orElseThrow(() -> new ProblemException(
                        "series.type_unknown", Map.of("docTypeCode", String.valueOf(registration.docTypeCode()))));

        validateScope(registration, type);

        if (ctx == null || !registration.ownerEntityId().equals(ctx.entityId())) {
            throw new ProblemException("series.owner_mismatch");
        }

        Optional<Series> existing = findByScope(registration);

        if (existing.isPresent()) {
            if ("CLOSED".equals(existing.get().status())) {
                throw new ProblemException(
                        "series.closed", Map.of("prefix", existing.get().prefix()));
            }
            return existing.get().seriesId();
        }

        UUID seriesId = Ids.next();
        String prefix = prefixOf(registration);

        // Two registrations of the same series at once (a location created while its shop
        // enrols a till) both passed the lookup above; the unique key decides, and the loser
        // reads the winner's row instead of failing on the violation.
        int inserted = jdbc.update(
                """
                insert into kernel.numbering_series (
                    series_id, doc_type_code, series_scope, owner_entity_id, location_id, till_position_id,
                    prefix, holder_device_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (doc_type_code, owner_entity_id, location_id, till_position_id) do nothing
                """,
                seriesId,
                registration.docTypeCode(),
                registration.scope().name(),
                registration.ownerEntityId(),
                registration.locationId(),
                registration.tillPositionId(),
                prefix,
                registration.holderDeviceId());

        if (inserted == 0) {
            return findByScope(registration)
                    .orElseThrow(() -> new IllegalStateException("The series " + prefix
                            + " was neither inserted nor found; is its row-level security" + " hiding it?"))
                    .seriesId();
        }

        audit.record(
                AUDIT_REGISTERED,
                Subject.of("numbering_series", seriesId),
                null,
                Map.of(
                        "docTypeCode", registration.docTypeCode(),
                        "scope", registration.scope().name(),
                        "prefix", prefix,
                        "holderDeviceId", String.valueOf(registration.holderDeviceId())),
                ctx);

        events.publish(
                new SeriesRegistered(seriesId, registration.docTypeCode(), registration.scope(), ctx.entityId()));

        return seriesId;
    }

    @Override
    public void holderChange(Collection<UUID> seriesIds, UUID deviceId, ScopeContext ctx) {
        requireTransaction("holderChange");

        if (deviceId == null) {
            throw new ProblemException("series.device_required");
        }

        for (UUID seriesId : seriesIds) {
            Series series = findById(seriesId).orElseThrow(() -> new ProblemException("series.not_found"));

            if ("CLOSED".equals(series.status())) {
                throw new ProblemException("series.closed", Map.of("prefix", series.prefix()));
            }

            if (deviceId.equals(series.holderDeviceId())) {
                continue;
            }

            jdbc.update(
                    "update kernel.numbering_series set holder_device_id = ? where series_id = ?", deviceId, seriesId);

            audit.record(
                    AUDIT_HOLDER_CHANGED,
                    Subject.of("numbering_series", seriesId),
                    Map.of("holderDeviceId", String.valueOf(series.holderDeviceId())),
                    Map.of("holderDeviceId", deviceId.toString()),
                    ctx);

            events.publish(new SeriesHolderChanged(seriesId, series.holderDeviceId(), deviceId, ctx.entityId()));
        }
    }

    @Override
    public void closeSeries(UUID seriesId, ScopeContext ctx) {
        requireTransaction("closeSeries");

        Series series = findById(seriesId).orElseThrow(() -> new ProblemException("series.not_found"));

        if ("CLOSED".equals(series.status())) {
            return;
        }

        jdbc.update("update kernel.numbering_series set status = 'CLOSED' where series_id = ?", seriesId);

        audit.record(
                AUDIT_CLOSED,
                Subject.of("numbering_series", seriesId),
                Map.of("status", "ACTIVE"),
                Map.of("status", "CLOSED", "lastNumber", String.valueOf(series.nextNumber() - 1)),
                ctx);

        events.publish(new SeriesClosed(seriesId, ctx.entityId()));
    }

    @Override
    public List<UUID> activeSeriesOf(UUID ownerEntityId, UUID locationId, UUID tillPositionId) {
        if (locationId == null) {
            throw new IllegalArgumentException(
                    "activeSeriesOf needs a location: entity series are not held by a device");
        }
        return jdbc.queryForList(
                """
                select series_id from kernel.numbering_series
                 where owner_entity_id = ? and location_id = ?
                   and till_position_id is not distinct from ?
                   and status = 'ACTIVE'
                 order by doc_type_code
                """,
                UUID.class,
                ownerEntityId,
                locationId,
                tillPositionId);
    }

    /**
     * The series a document of this type draws from, for this issuer, at this place: the
     * finest series that exists, from the position down to the entity (doc 18 section 5.5:
     * a GRN at a shop from the shop's LOCATION series, at a warehouse from the ENTITY series).
     */
    Optional<Series> seriesFor(String docTypeCode, UUID ownerEntityId, UUID locationId, UUID tillPositionId) {
        if (tillPositionId != null && locationId != null) {
            Optional<Series> atPosition = findByKey(docTypeCode, ownerEntityId, locationId, tillPositionId);
            if (atPosition.isPresent()) {
                return atPosition;
            }
        }
        if (locationId != null) {
            Optional<Series> atLocation = findByKey(docTypeCode, ownerEntityId, locationId, null);
            if (atLocation.isPresent()) {
                return atLocation;
            }
        }
        return findByKey(docTypeCode, ownerEntityId, null, null);
    }

    /**
     * The next number of a series, by the single-row update that makes the series gapless: the
     * row lock serialises concurrent issuers, and a rollback of the caller's transaction gives
     * the number back with everything else (doc 18 section 5.5, step 2).
     */
    long takeNumber(UUID seriesId) {
        List<Long> taken = jdbc.queryForList(
                """
                update kernel.numbering_series
                   set next_number = next_number + 1
                 where series_id = ? and status = 'ACTIVE'
                returning next_number - 1
                """,
                Long.class,
                seriesId);

        if (taken.isEmpty()) {
            throw new ProblemException("series.closed");
        }

        return taken.get(0);
    }

    private static void validateScope(SeriesRegistration registration, DocumentType type) {
        SeriesScope scope = registration.scope();

        boolean idsFit =
                switch (scope) {
                    case ENTITY -> registration.locationId() == null && registration.tillPositionId() == null;
                    case LOCATION ->
                        registration.locationId() != null
                                && registration.tillPositionId() == null
                                && registration.locationCode() != null;
                    case TILL_POSITION ->
                        registration.locationId() != null
                                && registration.tillPositionId() != null
                                && registration.locationCode() != null
                                && registration.positionNo() != null;
                };

        // A type numbers at its own scope or a coarser one (a warehouse GRN from the ENTITY
        // series), never a finer one: no till receipts from an entity-wide counter.
        boolean scopeFits = scope.ordinal() <= type.seriesScope().ordinal();

        if (!idsFit
                || !scopeFits
                || registration.ownerEntityId() == null
                || registration.entityCode() == null
                || registration.entityCode().isBlank()) {
            throw new ProblemException(
                    "series.scope_invalid", Map.of("docTypeCode", type.code(), "scope", scope.name()));
        }
    }

    static String prefixOf(SeriesRegistration registration) {
        StringBuilder prefix = new StringBuilder(registration.entityCode().strip());
        if (registration.scope() != SeriesScope.ENTITY) {
            prefix.append('-').append(registration.locationCode().strip());
        }
        if (registration.scope() == SeriesScope.TILL_POSITION) {
            prefix.append("-T").append(registration.positionNo());
        }
        prefix.append('-').append(registration.docTypeCode());

        if (prefix.length() > 24) {
            throw new ProblemException("series.prefix_too_long", Map.of("prefix", prefix.toString()));
        }
        return prefix.toString();
    }

    private Optional<Series> findByScope(SeriesRegistration registration) {
        return findByKey(
                registration.docTypeCode(),
                registration.ownerEntityId(),
                registration.locationId(),
                registration.tillPositionId());
    }

    private Optional<Series> findByKey(String docTypeCode, UUID ownerEntityId, UUID locationId, UUID tillPositionId) {
        return jdbc
                .query(
                        Series.SELECT + " where doc_type_code = ? and owner_entity_id = ?"
                                + " and location_id is not distinct from ? and till_position_id is not distinct from ?",
                        Series.ROW,
                        docTypeCode,
                        ownerEntityId,
                        locationId,
                        tillPositionId)
                .stream()
                .findFirst();
    }

    Optional<Series> findById(UUID seriesId) {
        return jdbc.query(Series.SELECT + " where series_id = ?", Series.ROW, seriesId).stream()
                .findFirst();
    }

    private static void requireTransaction(String method) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("NumberingService." + method
                    + " was called outside a transaction; call it inside the handler's @Transactional method");
        }
    }
}
