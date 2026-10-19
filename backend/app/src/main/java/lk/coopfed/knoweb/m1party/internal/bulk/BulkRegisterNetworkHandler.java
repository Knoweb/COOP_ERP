package lk.coopfed.knoweb.m1party.internal.bulk;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentType;
import lk.coopfed.knoweb.kernel.api.DocumentTypes;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.PinHasher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.kernel.api.SeriesScope;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.BulkNetworkRegistered;
import lk.coopfed.knoweb.m1party.api.BulkNetworkReport;
import lk.coopfed.knoweb.m1party.api.BulkNetworkReport.Problem;
import lk.coopfed.knoweb.m1party.api.BulkRegisterNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles bulk registration of a retail network (CAT-08): shops, till positions, users and
 * devices in a single batch process within one transaction.
 *
 * <p>All-or-none: every row is validated first. If any rule fails, nothing is committed and a
 * detailed {@link BulkNetworkReport} is returned. When all items pass, batch inserts minimize
 * database roundtrips and Argon2id PIN hashing runs with bounded concurrency and heap usage to
 * ensure rapid execution well under 60 seconds for at least 40 shops, 120 users, and 60 devices.
 */
@Service
@CommandHandler(permission = "prt.location.register")
public class BulkRegisterNetworkHandler implements Handles<BulkRegisterNetwork, BulkNetworkReport> {

    private static final Logger log = LoggerFactory.getLogger(BulkRegisterNetworkHandler.class);

    public static final String AUDIT_NETWORK_REGISTERED = "NETWORK_BULK_REGISTERED";

    static final int MIN_CONCURRENCY = 1;
    static final int MAX_CONCURRENCY = 4;

    /**
     * Diagnostic telemetry captured during bulk network registration (CAT-08) execution.
     * Captured ONLY when diagnostic mode is explicitly enabled.
     */
    public record DiagnosticTelemetry(
            UUID ownerEntityId,
            Instant recordedAt,
            int availableProcessors,
            String cpuQuota,
            String cpuStat,
            int activeConcurrency,
            long totalHandlerDurationMs,
            long hashingDurationMs,
            long databaseInsertsDurationMs,
            long seriesRegistrationDurationMs,
            long gcCollectionsDelta,
            long gcTimeDeltaMs,
            String gcDetails,
            long heapUsedBeforeMb,
            long heapUsedAfterMb,
            long heapCommittedAfterMb,
            long heapMaxMb) {

        public String formattedReport(long endpointDurationMs) {
            return String.format(
                    """
                    === CAT-08 DIAGNOSTIC TELEMETRY ===
                    Timestamp: %s | Entity: %s
                    Processors Available: %d | Linux Cgroup CPU Quota: %s
                    Linux Cgroup CPU Stat (Throttling Counters): %s
                    Active Hashing Concurrency: %d [Safe Diagnostic Range: %d..%d]
                    Durations:
                      - Endpoint Elapsed (Client HTTP Round-Trip): %d ms (%.2f s)
                      - Handler Execution (Transactional Boundary): %d ms (%.2f s)
                          * Argon2id Hashing (Wall-Clock): %d ms (%.2f s) [%.1f%% of handler]
                          * Database Batch Inserts (Wall-Clock): %d ms (%.2f s) [%.1f%% of handler]
                          * Series Registrations (Wall-Clock): %d ms (%.2f s) [%.1f%% of handler]
                          * Validation & Preparation: %d ms
                    JVM GC Activity (Cumulative during Handler):
                      - Collections Delta: %d
                      - Cumulative Collection Time Delta: %d ms (reported by GarbageCollectorMXBean; includes concurrent and pause time, NOT solely stop-the-world pause)
                      - Collector Breakdown: %s
                    JVM Heap Memory:
                      - Used: %d MB -> %d MB
                      - Committed: %d MB | Max: %d MB
                    ===================================""",
                    recordedAt,
                    ownerEntityId,
                    availableProcessors,
                    cpuQuota,
                    cpuStat,
                    activeConcurrency,
                    MIN_CONCURRENCY,
                    MAX_CONCURRENCY,
                    endpointDurationMs,
                    endpointDurationMs / 1000.0,
                    totalHandlerDurationMs,
                    totalHandlerDurationMs / 1000.0,
                    hashingDurationMs,
                    hashingDurationMs / 1000.0,
                    (hashingDurationMs * 100.0 / Math.max(1, totalHandlerDurationMs)),
                    databaseInsertsDurationMs,
                    databaseInsertsDurationMs / 1000.0,
                    (databaseInsertsDurationMs * 100.0 / Math.max(1, totalHandlerDurationMs)),
                    seriesRegistrationDurationMs,
                    seriesRegistrationDurationMs / 1000.0,
                    (seriesRegistrationDurationMs * 100.0 / Math.max(1, totalHandlerDurationMs)),
                    Math.max(
                            0,
                            totalHandlerDurationMs
                                    - hashingDurationMs
                                    - databaseInsertsDurationMs
                                    - seriesRegistrationDurationMs),
                    gcCollectionsDelta,
                    gcTimeDeltaMs,
                    gcDetails,
                    heapUsedBeforeMb,
                    heapUsedAfterMb,
                    heapCommittedAfterMb,
                    heapMaxMb);
        }

        public String formattedReport() {
            return formattedReport(totalHandlerDurationMs);
        }
    }

    private static final AtomicReference<DiagnosticTelemetry> telemetryRef = new AtomicReference<>();

    /**
     * Reads the most recent diagnostic telemetry, if any.
     * Retained for inspectability; tests should prefer {@link #consumeTelemetry()}.
     */
    public static DiagnosticTelemetry lastTelemetry() {
        return telemetryRef.get();
    }

    /**
     * Consumes and atomically clears the most recent diagnostic telemetry, guaranteeing test isolation.
     * Prevents cross-test or stale telemetry leakage.
     */
    public static DiagnosticTelemetry consumeTelemetry() {
        return telemetryRef.getAndSet(null);
    }

    /**
     * Explicitly clears any captured diagnostic telemetry.
     */
    public static void clearTelemetry() {
        telemetryRef.set(null);
    }

    static boolean isDiagnosticsEnabled() {
        String prop = System.getProperty("coop-erp.bulk-register.diagnostics");
        if (prop != null) {
            return Boolean.parseBoolean(prop.trim());
        }
        String env = System.getenv("COOP_ERP_BULK_DIAGNOSTICS");
        if (env != null) {
            return "true".equalsIgnoreCase(env.trim());
        }
        return false;
    }

    static int resolveConcurrency() {
        int defaultConcurrency =
                Math.max(MIN_CONCURRENCY, Math.min(Runtime.getRuntime().availableProcessors(), MAX_CONCURRENCY));
        if (!isDiagnosticsEnabled()) {
            return defaultConcurrency;
        }

        String raw = System.getProperty("coop-erp.bulk-register.argon2-concurrency");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("COOP_ERP_BULK_CONCURRENCY");
        }
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed >= MIN_CONCURRENCY && parsed <= MAX_CONCURRENCY) {
                    return parsed;
                }
                log.warn(
                        "Ignoring out-of-range diagnostic concurrency override: {}. Allowed range is [{}..{}]; defaulting to {}.",
                        parsed,
                        MIN_CONCURRENCY,
                        MAX_CONCURRENCY,
                        defaultConcurrency);
            } catch (NumberFormatException e) {
                log.warn(
                        "Ignoring unparseable diagnostic concurrency override: '{}'; defaulting to {}.",
                        raw.trim(),
                        defaultConcurrency);
            }
        }
        return defaultConcurrency;
    }

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
    private static final Pattern PIN_PATTERN = Pattern.compile("^[0-9]{4,6}$");

    private record PreparedPosition(UUID id, UUID locationId, int positionNo, UUID ownerEntityId) {}

    private record PreparedUser(
            UUID id,
            UUID homeEntityId,
            String username,
            String displayName,
            String language,
            String userKind,
            String pinHash,
            Instant pinChangedAt,
            String status) {}

    private record PreparedDevice(
            UUID id,
            String hardwareSerial,
            String deviceKind,
            UUID ownerEntityId,
            UUID locationId,
            UUID positionId,
            String appVersion,
            String status) {}

    private final JdbcTemplate jdbc;
    private final PinHasher pinHasher;
    private final NumberingService numbering;
    private final DocumentTypes documentTypes;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public BulkRegisterNetworkHandler(
            JdbcTemplate jdbc,
            PinHasher pinHasher,
            NumberingService numbering,
            DocumentTypes documentTypes,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.pinHasher = pinHasher;
        this.numbering = numbering;
        this.documentTypes = documentTypes;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BulkNetworkReport handle(BulkRegisterNetwork command, ScopeContext scope) {
        boolean diag = isDiagnosticsEnabled();
        long handlerStart = diag ? System.nanoTime() : 0L;
        Map<String, Long> gcCountsBefore = diag ? currentGcCounts() : Map.of();
        Map<String, Long> gcTimesBefore = diag ? currentGcTimes() : Map.of();
        long heapUsedBeforeMb =
                diag ? ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024) : 0L;

        if (scope == null || scope.entityId() == null) {
            throw new ProblemException("m1.location.scope_required");
        }
        UUID ownerEntityId = scope.entityId();

        // 1. Verify owner entity exists
        String ownerEntityCode = getOwnerEntityCode(ownerEntityId);
        if (ownerEntityCode == null) {
            throw new ProblemException("m1.location.owner_not_trading");
        }

        List<BulkRegisterNetwork.Shop> shops = command == null ? List.of() : command.shops();
        List<BulkRegisterNetwork.User> users = command == null ? List.of() : command.users();
        List<BulkRegisterNetwork.Device> devices = command == null ? List.of() : command.devices();

        if (shops.isEmpty() && users.isEmpty() && devices.isEmpty()) {
            return new BulkNetworkReport(
                    BulkNetworkReport.REJECTED,
                    0,
                    0,
                    0,
                    0,
                    List.of(new Problem("GENERAL", "", "payload", "bulk.network.payload_empty")));
        }

        // 2. Pre-validations (All-or-None)
        List<Problem> problems = new ArrayList<>();
        validateShops(shops, ownerEntityId, problems);
        validateUsers(users, problems);
        validateDevices(devices, shops, ownerEntityId, problems);

        if (!problems.isEmpty()) {
            return new BulkNetworkReport(BulkNetworkReport.REJECTED, 0, 0, 0, 0, problems);
        }

        // 3. Prepare data and generate IDs
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // Map shopCode -> locationId
        Map<String, UUID> locationIds = new HashMap<>();
        for (BulkRegisterNetwork.Shop shop : shops) {
            locationIds.put(shop.locationCode().strip(), Ids.next());
        }

        // Prepare till positions
        List<PreparedPosition> preparedPositions = new ArrayList<>();
        Map<String, UUID> positionMap = new HashMap<>(); // key: "locationCode#positionNo" -> id
        Map<UUID, UUID> primaryTillMap = new HashMap<>(); // locationId -> primary till positionId

        for (BulkRegisterNetwork.Shop shop : shops) {
            UUID locId = locationIds.get(shop.locationCode().strip());
            List<Integer> positions = shop.tillPositions();
            for (Integer posNo : positions) {
                UUID posId = Ids.next();
                preparedPositions.add(new PreparedPosition(posId, locId, posNo, ownerEntityId));
                positionMap.put(shop.locationCode().strip() + "#" + posNo, posId);
                if (posNo == 1 && !primaryTillMap.containsKey(locId)) {
                    primaryTillMap.put(locId, posId);
                }
            }
        }

        // Prepare users with bounded parallel Argon2id PIN hashing.
        // Concurrency is configurable via coop-erp.bulk-register.argon2-concurrency / COOP_ERP_BULK_CONCURRENCY,
        // defaulting to at most 4 (and available CPU cores) to keep peak Argon2 memory bounded.
        Map<String, String> hashedPins = new ConcurrentHashMap<>();
        List<BulkRegisterNetwork.User> usersWithPin = users.stream()
                .filter(u -> u.pin() != null && !u.pin().isBlank())
                .toList();

        long hashingStart = diag ? System.nanoTime() : 0L;
        int concurrencyUsed = 0;
        if (!usersWithPin.isEmpty()) {
            concurrencyUsed = resolveConcurrency();
            final int activeConcurrency = concurrencyUsed;
            ThreadFactory threadFactory = r -> {
                Thread t = new Thread(r, "bulk-pin-hasher");
                t.setDaemon(true);
                return t;
            };

            try (ExecutorService pool = Executors.newFixedThreadPool(activeConcurrency, threadFactory)) {
                List<CompletableFuture<Void>> futures = usersWithPin.stream()
                        .map(u -> CompletableFuture.runAsync(
                                () -> {
                                    String norm = u.username().strip().toLowerCase(Locale.ROOT);
                                    String hash = pinHasher.hash(u.pin().strip());
                                    hashedPins.put(norm, hash);
                                },
                                pool))
                        .toList();

                try {
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .join();
                } catch (CompletionException e) {
                    if (e.getCause() instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException("Bulk PIN hashing failed", e.getCause());
                }
            }
        }
        long hashingDurationMs = diag ? (System.nanoTime() - hashingStart) / 1_000_000 : 0L;

        List<PreparedUser> preparedUsers = new ArrayList<>();
        for (BulkRegisterNetwork.User user : users) {
            UUID userId = Ids.next();
            String normUser = user.username().strip().toLowerCase(Locale.ROOT);
            String hash = hashedPins.get(normUser);
            String kind = user.userKind() == null || user.userKind().isBlank()
                    ? "TILL"
                    : user.userKind().strip();
            String lang = user.language() == null || user.language().isBlank()
                    ? "en"
                    : user.language().strip();
            String status = hash != null ? "ACTIVE" : "PENDING";
            preparedUsers.add(new PreparedUser(
                    userId,
                    ownerEntityId,
                    normUser,
                    user.displayName().strip(),
                    lang,
                    kind,
                    hash,
                    hash != null ? now : null,
                    status));
        }

        // Prepare devices
        List<PreparedDevice> preparedDevices = new ArrayList<>();
        Map<UUID, UUID> positionDeviceMap = new HashMap<>();
        for (BulkRegisterNetwork.Device dev : devices) {
            UUID devId = Ids.next();
            String serial = dev.hardwareSerial().strip();
            String kind = dev.deviceKind() == null || dev.deviceKind().isBlank()
                    ? "POS_TERMINAL"
                    : dev.deviceKind().strip();
            String appVer = dev.appVersion() == null || dev.appVersion().isBlank()
                    ? "1.0.0"
                    : dev.appVersion().strip();
            String locCode = dev.locationCode().strip();
            UUID locId = locationIds.containsKey(locCode)
                    ? locationIds.get(locCode)
                    : lookupLocationId(ownerEntityId, locCode);

            UUID posId = null;
            if (dev.positionNo() != null) {
                String posKey = locCode + "#" + dev.positionNo();
                posId = positionMap.containsKey(posKey)
                        ? positionMap.get(posKey)
                        : lookupPositionId(locId, dev.positionNo());
            }

            if (posId != null) {
                positionDeviceMap.put(posId, devId);
            }

            String status = posId != null ? "ACTIVE" : "ENROLLED";
            preparedDevices.add(new PreparedDevice(devId, serial, kind, ownerEntityId, locId, posId, appVer, status));
        }

        // 4. Batch Inserts to Database
        long dbStart = diag ? System.nanoTime() : 0L;
        batchInsertLocations(shops, locationIds, ownerEntityId);
        batchInsertPositions(preparedPositions);
        batchInsertUsers(preparedUsers);
        batchInsertDevices(preparedDevices, now);

        // 5. Update primary till positions on shops
        if (!primaryTillMap.isEmpty()) {
            List<Map.Entry<UUID, UUID>> primaryTillEntries = new ArrayList<>(primaryTillMap.entrySet());
            jdbc.batchUpdate(
                    "UPDATE party.location SET primary_till_position_id = ? WHERE location_id = ?",
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            Map.Entry<UUID, UUID> entry = primaryTillEntries.get(i);
                            ps.setObject(1, entry.getValue());
                            ps.setObject(2, entry.getKey());
                        }

                        @Override
                        public int getBatchSize() {
                            return primaryTillEntries.size();
                        }
                    });
        }
        long databaseInsertsDurationMs = diag ? (System.nanoTime() - dbStart) / 1_000_000 : 0L;

        // 6. Register Numbering Series for locations and till positions
        long seriesStart = diag ? System.nanoTime() : 0L;
        List<DocumentType> locationTypes = documentTypes.all().stream()
                .filter(t -> t.seriesScope() == SeriesScope.LOCATION)
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .toList();

        List<DocumentType> positionTypes = documentTypes.all().stream()
                .filter(t -> t.seriesScope() == SeriesScope.TILL_POSITION)
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .toList();

        Map<UUID, String> locationCodeById = new HashMap<>();
        for (BulkRegisterNetwork.Shop shop : shops) {
            String code = shop.locationCode().strip();
            UUID locId = locationIds.get(code);
            locationCodeById.put(locId, code);
            for (DocumentType type : locationTypes) {
                numbering.registerSeries(
                        SeriesRegistration.forLocation(type.code(), ownerEntityId, locId, ownerEntityCode, code, null),
                        scope);
            }
        }

        for (PreparedPosition pos : preparedPositions) {
            String shopCode = locationCodeById.getOrDefault(pos.locationId(), "");
            UUID holderDeviceId = positionDeviceMap.get(pos.id());

            for (DocumentType type : positionTypes) {
                numbering.registerSeries(
                        SeriesRegistration.forTillPosition(
                                type.code(),
                                ownerEntityId,
                                pos.locationId(),
                                pos.id(),
                                ownerEntityCode,
                                shopCode,
                                pos.positionNo(),
                                holderDeviceId),
                        scope);
            }
        }
        long seriesRegistrationDurationMs = diag ? (System.nanoTime() - seriesStart) / 1_000_000 : 0L;

        // 7. Audit & Domain Event
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entityId", ownerEntityId);
        summary.put("shopsCount", shops.size());
        summary.put("positionsCount", preparedPositions.size());
        summary.put("usersCount", preparedUsers.size());
        summary.put("devicesCount", preparedDevices.size());

        audit.record(AUDIT_NETWORK_REGISTERED, Subject.of("entity", ownerEntityId), null, summary, scope);
        events.publish(new BulkNetworkRegistered(
                ownerEntityId, shops.size(), preparedPositions.size(), preparedUsers.size(), preparedDevices.size()));

        if (diag) {
            long totalHandlerDurationMs = (System.nanoTime() - handlerStart) / 1_000_000;
            Map<String, Long> gcCountsAfter = currentGcCounts();
            Map<String, Long> gcTimesAfter = currentGcTimes();
            MemoryUsage heapUsageAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long heapUsedAfterMb = heapUsageAfter.getUsed() / (1024 * 1024);
            long heapCommittedAfterMb = heapUsageAfter.getCommitted() / (1024 * 1024);
            long heapMaxMb = heapUsageAfter.getMax() / (1024 * 1024);

            long gcCollectionsDelta = sumDeltas(gcCountsBefore, gcCountsAfter);
            long gcTimeDeltaMs = sumDeltas(gcTimesBefore, gcTimesAfter);
            String gcDetails =
                    formatGcDetails(diffMaps(gcCountsBefore, gcCountsAfter), diffMaps(gcTimesBefore, gcTimesAfter));

            DiagnosticTelemetry telemetry = new DiagnosticTelemetry(
                    ownerEntityId,
                    clock.instant(),
                    Runtime.getRuntime().availableProcessors(),
                    readCpuQuota(),
                    readCpuStat(),
                    concurrencyUsed,
                    totalHandlerDurationMs,
                    hashingDurationMs,
                    databaseInsertsDurationMs,
                    seriesRegistrationDurationMs,
                    gcCollectionsDelta,
                    gcTimeDeltaMs,
                    gcDetails,
                    heapUsedBeforeMb,
                    heapUsedAfterMb,
                    heapCommittedAfterMb,
                    heapMaxMb);
            telemetryRef.set(telemetry);
            if (log.isInfoEnabled()) {
                log.info("Bulk network registration diagnostic telemetry:\n{}", telemetry.formattedReport());
            }
        }

        return new BulkNetworkReport(
                BulkNetworkReport.REGISTERED,
                shops.size(),
                preparedPositions.size(),
                preparedUsers.size(),
                preparedDevices.size(),
                List.of());
    }

    private void validateShops(List<BulkRegisterNetwork.Shop> shops, UUID ownerEntityId, List<Problem> problems) {
        Set<String> seenCodes = new HashSet<>();
        List<String> codesToCheck = new ArrayList<>();

        for (BulkRegisterNetwork.Shop shop : shops) {
            if (shop.locationCode() == null || shop.locationCode().isBlank()) {
                problems.add(new Problem("SHOP", "", "locationCode", "bulk.shop.code_required"));
                continue;
            }
            String code = shop.locationCode().strip();
            if (!seenCodes.add(code)) {
                problems.add(new Problem("SHOP", code, "locationCode", "bulk.shop.code_duplicate"));
            } else {
                codesToCheck.add(code);
            }

            if (shop.nameEn() == null || shop.nameEn().isBlank()) {
                problems.add(new Problem("SHOP", code, "nameEn", "bulk.shop.name_required"));
            }

            Set<Integer> seenPositions = new HashSet<>();
            for (Integer pos : shop.tillPositions()) {
                if (pos == null || pos <= 0) {
                    problems.add(new Problem("SHOP", code, "positionNo", "bulk.position.number_invalid"));
                } else if (!seenPositions.add(pos)) {
                    problems.add(new Problem("SHOP", code, "positionNo", "bulk.position.number_duplicate"));
                }
            }
        }

        if (!codesToCheck.isEmpty()) {
            List<String> existing = jdbc.query(
                    "SELECT location_code FROM party.location WHERE owner_entity_id = ? AND location_code IN ("
                            + inSql(codesToCheck.size()) + ")",
                    ps -> {
                        ps.setObject(1, ownerEntityId);
                        for (int i = 0; i < codesToCheck.size(); i++) {
                            ps.setString(i + 2, codesToCheck.get(i));
                        }
                    },
                    (rs, rowNum) -> rs.getString("location_code"));

            for (String exist : existing) {
                problems.add(new Problem("SHOP", exist, "locationCode", "bulk.shop.code_duplicate"));
            }
        }
    }

    private void validateUsers(List<BulkRegisterNetwork.User> users, List<Problem> problems) {
        Set<String> seenUsernames = new HashSet<>();
        List<String> usernamesToCheck = new ArrayList<>();

        for (BulkRegisterNetwork.User user : users) {
            if (user.username() == null || user.username().isBlank()) {
                problems.add(new Problem("USER", "", "username", "bulk.user.username_required"));
                continue;
            }
            String norm = user.username().strip().toLowerCase(Locale.ROOT);
            if (!USERNAME_PATTERN.matcher(norm).matches()) {
                problems.add(new Problem("USER", norm, "username", "bulk.user.username_invalid"));
            } else if (!seenUsernames.add(norm)) {
                problems.add(new Problem("USER", norm, "username", "bulk.user.username_duplicate"));
            } else {
                usernamesToCheck.add(norm);
            }

            if (user.displayName() == null || user.displayName().isBlank()) {
                problems.add(new Problem("USER", norm, "displayName", "bulk.user.display_name_required"));
            }

            if (user.pin() != null && !user.pin().isBlank()) {
                String pin = user.pin().strip();
                if (!PIN_PATTERN.matcher(pin).matches()) {
                    problems.add(new Problem("USER", norm, "pin", "bulk.user.pin_invalid"));
                }
            }
        }

        if (!usernamesToCheck.isEmpty()) {
            List<String> existing = jdbc.query(
                    "SELECT lower(username) FROM security.app_user WHERE lower(username) IN ("
                            + inSql(usernamesToCheck.size()) + ")",
                    ps -> {
                        for (int i = 0; i < usernamesToCheck.size(); i++) {
                            ps.setString(i + 1, usernamesToCheck.get(i));
                        }
                    },
                    (rs, rowNum) -> rs.getString(1));

            for (String exist : existing) {
                problems.add(new Problem("USER", exist, "username", "bulk.user.username_duplicate"));
            }
        }
    }

    private void validateDevices(
            List<BulkRegisterNetwork.Device> devices,
            List<BulkRegisterNetwork.Shop> shops,
            UUID ownerEntityId,
            List<Problem> problems) {
        Set<String> seenSerials = new HashSet<>();
        List<String> serialsToCheck = new ArrayList<>();

        Set<String> payloadShopCodes = new HashSet<>();
        Map<String, Set<Integer>> payloadPositions = new HashMap<>();
        for (BulkRegisterNetwork.Shop shop : shops) {
            String code = shop.locationCode().strip();
            payloadShopCodes.add(code);
            payloadPositions.put(code, new HashSet<>(shop.tillPositions()));
        }

        Set<String> assignedLanes = new HashSet<>();

        for (BulkRegisterNetwork.Device dev : devices) {
            if (dev.hardwareSerial() == null || dev.hardwareSerial().isBlank()) {
                problems.add(new Problem("DEVICE", "", "hardwareSerial", "bulk.device.serial_required"));
                continue;
            }
            String serial = dev.hardwareSerial().strip();
            if (!seenSerials.add(serial)) {
                problems.add(new Problem("DEVICE", serial, "hardwareSerial", "bulk.device.serial_duplicate"));
            } else {
                serialsToCheck.add(serial);
            }

            if (dev.locationCode() == null || dev.locationCode().isBlank()) {
                problems.add(new Problem("DEVICE", serial, "locationCode", "bulk.device.location_unknown"));
                continue;
            }
            String locCode = dev.locationCode().strip();
            boolean inPayload = payloadShopCodes.contains(locCode);
            if (!inPayload && !locationExistsInDb(ownerEntityId, locCode)) {
                problems.add(new Problem("DEVICE", serial, "locationCode", "bulk.device.location_unknown"));
                continue;
            }

            if (dev.positionNo() != null) {
                String laneKey = locCode + "#" + dev.positionNo();
                if (!assignedLanes.add(laneKey)) {
                    problems.add(new Problem("DEVICE", serial, "positionNo", "bulk.device.position_occupied"));
                }

                if (inPayload) {
                    Set<Integer> positions = payloadPositions.get(locCode);
                    if (!positions.contains(dev.positionNo())) {
                        problems.add(new Problem("DEVICE", serial, "positionNo", "bulk.device.position_unknown"));
                    }
                } else {
                    UUID locId = lookupLocationId(ownerEntityId, locCode);
                    if (lookupPositionId(locId, dev.positionNo()) == null) {
                        problems.add(new Problem("DEVICE", serial, "positionNo", "bulk.device.position_unknown"));
                    }
                }
            }
        }

        if (!serialsToCheck.isEmpty()) {
            List<String> existing = jdbc.query(
                    "SELECT hardware_serial FROM party.device WHERE hardware_serial IN (" + inSql(serialsToCheck.size())
                            + ")",
                    ps -> {
                        for (int i = 0; i < serialsToCheck.size(); i++) {
                            ps.setString(i + 1, serialsToCheck.get(i));
                        }
                    },
                    (rs, rowNum) -> rs.getString("hardware_serial"));

            for (String exist : existing) {
                problems.add(new Problem("DEVICE", exist, "hardwareSerial", "bulk.device.serial_duplicate"));
            }
        }
    }

    private void batchInsertLocations(
            List<BulkRegisterNetwork.Shop> shops, Map<String, UUID> locationIds, UUID ownerEntityId) {
        String sql =
                """
                INSERT INTO party.location (
                    location_id, owner_entity_id, location_code, location_type,
                    name_en, name_si, name_ta, address, district,
                    geo_lat, geo_lng, language, trading_hours, size_band,
                    connectivity_spec_met, status
                ) VALUES (?, ?, ?, 'SHOP', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, false, 'PLANNED')
                """;

        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                BulkRegisterNetwork.Shop s = shops.get(i);
                UUID id = locationIds.get(s.locationCode().strip());
                ps.setObject(1, id);
                ps.setObject(2, ownerEntityId);
                ps.setString(3, s.locationCode().strip());
                ps.setString(4, s.nameEn().strip());
                ps.setString(5, s.nameSi());
                ps.setString(6, s.nameTa());
                ps.setString(7, s.address());
                ps.setString(8, s.district());
                if (s.geoLat() != null) ps.setDouble(9, s.geoLat());
                else ps.setNull(9, Types.NUMERIC);
                if (s.geoLng() != null) ps.setDouble(10, s.geoLng());
                else ps.setNull(10, Types.NUMERIC);
                ps.setString(11, s.language() == null ? "en" : s.language());
                ps.setString(12, "[]");
                ps.setString(13, s.sizeBand());
            }

            @Override
            public int getBatchSize() {
                return shops.size();
            }
        });
    }

    private void batchInsertPositions(List<PreparedPosition> positions) {
        if (positions.isEmpty()) return;
        String sql =
                """
                INSERT INTO party.till_position (
                    till_position_id, location_id, position_no, status, owner_entity_id
                ) VALUES (?, ?, ?, 'ACTIVE', ?)
                """;

        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PreparedPosition p = positions.get(i);
                ps.setObject(1, p.id());
                ps.setObject(2, p.locationId());
                ps.setInt(3, p.positionNo());
                ps.setObject(4, p.ownerEntityId());
            }

            @Override
            public int getBatchSize() {
                return positions.size();
            }
        });
    }

    private void batchInsertUsers(List<PreparedUser> users) {
        if (users.isEmpty()) return;
        String sql =
                """
                INSERT INTO security.app_user (
                    user_id, home_entity_id, username, display_name, language,
                    user_kind, pin_hash, pin_changed_at, pin_history, sandbox, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, false, ?)
                """;

        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PreparedUser u = users.get(i);
                ps.setObject(1, u.id());
                ps.setObject(2, u.homeEntityId());
                ps.setString(3, u.username());
                ps.setString(4, u.displayName());
                ps.setString(5, u.language());
                ps.setString(6, u.userKind());
                ps.setString(7, u.pinHash());
                if (u.pinChangedAt() != null) {
                    ps.setTimestamp(8, Timestamp.from(u.pinChangedAt()));
                } else {
                    ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
                }
                if (u.pinHash() != null) {
                    ps.setString(9, "[\"" + u.pinHash() + "\"]");
                } else {
                    ps.setString(9, "[]");
                }
                ps.setString(10, u.status());
            }

            @Override
            public int getBatchSize() {
                return users.size();
            }
        });
    }

    private void batchInsertDevices(List<PreparedDevice> devices, Instant now) {
        if (devices.isEmpty()) return;
        String sql =
                """
                INSERT INTO party.device (
                    device_id, hardware_serial, device_kind, owner_entity_id,
                    location_id, current_till_position_id, enrolled_at, app_version, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PreparedDevice d = devices.get(i);
                ps.setObject(1, d.id());
                ps.setString(2, d.hardwareSerial());
                ps.setString(3, d.deviceKind());
                ps.setObject(4, d.ownerEntityId());
                ps.setObject(5, d.locationId());
                if (d.positionId() != null) {
                    ps.setObject(6, d.positionId());
                } else {
                    ps.setNull(6, Types.OTHER);
                }
                ps.setTimestamp(7, Timestamp.from(now));
                ps.setString(8, d.appVersion());
                ps.setString(9, d.status());
            }

            @Override
            public int getBatchSize() {
                return devices.size();
            }
        });
    }

    private String getOwnerEntityCode(UUID entityId) {
        List<String> codes = jdbc.query(
                "SELECT entity_code FROM party.entity WHERE entity_id = ?",
                ps -> ps.setObject(1, entityId),
                (rs, rowNum) -> rs.getString("entity_code"));
        return codes.isEmpty() ? null : codes.get(0);
    }

    private boolean locationExistsInDb(UUID ownerEntityId, String locationCode) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM party.location WHERE owner_entity_id = ? AND location_code = ?",
                Integer.class,
                ownerEntityId,
                locationCode);
        return count != null && count > 0;
    }

    private UUID lookupLocationId(UUID ownerEntityId, String locationCode) {
        List<UUID> ids = jdbc.query(
                "SELECT location_id FROM party.location WHERE owner_entity_id = ? AND location_code = ?",
                ps -> {
                    ps.setObject(1, ownerEntityId);
                    ps.setString(2, locationCode);
                },
                (rs, rowNum) -> (UUID) rs.getObject("location_id"));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private UUID lookupPositionId(UUID locationId, int positionNo) {
        if (locationId == null) return null;
        List<UUID> ids = jdbc.query(
                "SELECT till_position_id FROM party.till_position WHERE location_id = ? AND position_no = ?",
                ps -> {
                    ps.setObject(1, locationId);
                    ps.setInt(2, positionNo);
                },
                (rs, rowNum) -> (UUID) rs.getObject("till_position_id"));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static String inSql(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Map<String, Long> currentGcCounts() {
        Map<String, Long> map = new HashMap<>();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            map.put(b.getName(), b.getCollectionCount());
        }
        return map;
    }

    private static Map<String, Long> currentGcTimes() {
        Map<String, Long> map = new HashMap<>();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            map.put(b.getName(), b.getCollectionTime());
        }
        return map;
    }

    private static Map<String, Long> diffMaps(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> diff = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            long prev = before.getOrDefault(entry.getKey(), 0L);
            diff.put(entry.getKey(), Math.max(0L, entry.getValue() - prev));
        }
        return diff;
    }

    private static long sumDeltas(Map<String, Long> before, Map<String, Long> after) {
        long sum = 0;
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            long prev = before.getOrDefault(entry.getKey(), 0L);
            sum += Math.max(0L, entry.getValue() - prev);
        }
        return sum;
    }

    private static String formatGcDetails(Map<String, Long> countDeltas, Map<String, Long> timeDeltas) {
        StringBuilder sb = new StringBuilder();
        for (String name : countDeltas.keySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(name)
                    .append("=")
                    .append(countDeltas.getOrDefault(name, 0L))
                    .append(" collections (")
                    .append(timeDeltas.getOrDefault(name, 0L))
                    .append(" ms)");
        }
        return sb.toString();
    }

    private static String readCpuQuota() {
        try {
            Path cgroupV2 = Path.of("/sys/fs/cgroup/cpu.max");
            if (Files.exists(cgroupV2)) {
                return Files.readString(cgroupV2).trim();
            }
        } catch (Exception ignored) {
        }
        try {
            Path quota = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us");
            Path period = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_period_us");
            if (Files.exists(quota) && Files.exists(period)) {
                return Files.readString(quota).trim() + " / "
                        + Files.readString(period).trim();
            }
        } catch (Exception ignored) {
        }
        return "unrestricted/unknown";
    }

    private static String readCpuStat() {
        try {
            Path cgroupV2 = Path.of("/sys/fs/cgroup/cpu.stat");
            if (Files.exists(cgroupV2)) {
                return Files.readString(cgroupV2).trim().replace("\n", ", ");
            }
        } catch (Exception ignored) {
        }
        try {
            Path cgroupV1 = Path.of("/sys/fs/cgroup/cpu/cpu.stat");
            if (Files.exists(cgroupV1)) {
                return Files.readString(cgroupV1).trim().replace("\n", ", ");
            }
        } catch (Exception ignored) {
        }
        return "unrestricted/unknown";
    }
}
