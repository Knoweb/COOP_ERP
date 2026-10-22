package lk.coopfed.knoweb.m3pricing.internal.label;

import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m3pricing.api.LabelJobRequested;
import lk.coopfed.knoweb.m3pricing.api.PrintShelfLabels;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListStore;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the QUEUED label job of doc 23 section 3.6; the worker renders it later. */
@Service
@CommandHandler(permission = "prc.label.print")
public class PrintShelfLabelsHandler implements Handles<PrintShelfLabels, UUID> {

    static final String AUDIT_CREATED = "LABEL_JOB_CREATED";
    static final String TEMPLATE_KEY = "pricing.label_template";
    static final String DEFAULT_TEMPLATE = "default";
    private static final Pattern TEMPLATE_ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final JdbcTemplate jdbc;
    private final PartyQueries party;
    private final PriceListStore lists;
    private final ConfigRegistry config;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    @SuppressWarnings("java:S107") // command inputs and its audit/event dependencies
    PrintShelfLabelsHandler(
            JdbcTemplate jdbc,
            PartyQueries party,
            PriceListStore lists,
            ConfigRegistry config,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jdbc = jdbc;
        this.party = party;
        this.lists = lists;
        this.config = config;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Override
    @Transactional
    public UUID handle(PrintShelfLabels command, ScopeContext scope) {
        if (command == null
                || command.locationId() == null
                || command.skuIds() == null
                || command.skuIds().isEmpty()) {
            throw new ProblemException("request.invalid");
        }
        requireOwner(scope);
        List<UUID> skuIds = distinctSkus(command.skuIds());

        LocationView location = party.getLocation(command.locationId(), scope)
                .filter(found -> found.ownerEntityId().equals(scope.entityId()))
                .orElseThrow(() -> new ProblemException("m3.label.location_not_owned"));
        if (!"SHOP".equals(location.locationType())) {
            throw new ProblemException("m3.label.location_not_owned");
        }

        LocalDate printDate = PriceListRules.today(clock, businessZone);
        PriceListView list = lists.rootOfKind("RETAIL", scope.entityId())
                .flatMap(root -> lists.versionInForce(root, printDate))
                .orElseThrow(() -> new ProblemException("m3.label.retail_list_not_found"));
        Set<UUID> listed = lists.lines(list.priceListId()).stream()
                .map(PriceListLineView::skuId)
                .collect(java.util.stream.Collectors.toSet());
        List<UUID> missing =
                skuIds.stream().filter(sku -> !listed.contains(sku)).toList();
        if (!missing.isEmpty()) {
            throw new ProblemException("m3.label.sku_not_on_list", Map.of("skuId", missing.get(0)));
        }

        String template =
                config.getOrDefault(TEMPLATE_KEY, scope, DEFAULT_TEMPLATE).strip();
        if (!TEMPLATE_ID.matcher(template).matches()
                || getClass().getClassLoader().getResource("reports/templates/" + template + ".html") == null) {
            throw new ProblemException("m3.label.template_invalid", Map.of("template", template));
        }

        UUID jobId = Ids.next();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("insert into pricing.label_job"
                    + " (job_id, owner_entity_id, location_id, template, sku_ids, created_by, created_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?)");
            statement.setObject(1, jobId);
            statement.setObject(2, scope.entityId());
            statement.setObject(3, command.locationId());
            statement.setString(4, template);
            statement.setArray(5, connection.createArrayOf("uuid", skuIds.toArray(new UUID[0])));
            statement.setObject(6, scope.userId());
            statement.setTimestamp(7, java.sql.Timestamp.from(clock.instant()));
            return statement;
        });

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("locationId", command.locationId());
        after.put("template", template);
        after.put("requestedCount", skuIds.size());
        after.put("status", LabelJobStore.QUEUED);
        audit.record(AUDIT_CREATED, Subject.of("label_job", jobId), null, after, scope);
        events.publish(new LabelJobRequested(jobId, command.locationId(), skuIds.size()));
        return jobId;
    }

    private static void requireOwner(ScopeContext scope) {
        if (scope == null
                || !scope.hasActiveScope()
                || scope.userId() == null
                || scope.entityId() == null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
    }

    private static List<UUID> distinctSkus(List<UUID> requested) {
        if (requested.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ProblemException("request.invalid");
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(requested);
        if (unique.size() != requested.size()) {
            throw new ProblemException("request.invalid");
        }
        return List.copyOf(unique);
    }
}
