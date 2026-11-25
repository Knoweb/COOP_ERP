package lk.coopfed.knoweb.m1party.internal.user;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.IdentityProviderClient;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PinHasher;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.BulkImportUsers;
import lk.coopfed.knoweb.m1party.api.BulkUserReport;
import lk.coopfed.knoweb.m1party.api.UserActivated;
import lk.coopfed.knoweb.m1party.api.UserCreated;
import lk.coopfed.knoweb.m1party.api.UsersBulkImported;
import lk.coopfed.knoweb.m1party.internal.bulk.BulkUserCsvParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads users in bulk from a spreadsheet or payload with identity-provider provisioning (CAT-09).
 *
 * <p>Validates all-or-none. Provisions each user in Keycloak (with a password or temporary password),
 * creates platform user rows in {@code security.app_user}, and records audit and domain events
 * within a single transaction boundary.
 */
@Service
@CommandHandler(permission = "gov.user.manage")
public class BulkUserImporter implements Handles<BulkImportUsers, BulkUserReport> {

    public static final String AUDIT_USERS_BULK_IMPORTED = "USERS_BULK_IMPORTED";

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
    private static final Pattern PIN_PATTERN = Pattern.compile("^[0-9]{4,6}$");

    private final AppUserRepository users;
    private final IdentityProviderClient provider;
    private final PinHasher pinHasher;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public BulkUserImporter(
            AppUserRepository users,
            IdentityProviderClient provider,
            PinHasher pinHasher,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.users = users;
        this.provider = provider;
        this.pinHasher = pinHasher;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BulkUserReport handle(BulkImportUsers command, ScopeContext scope) {
        UserGuards.requireEntityScope(scope);

        List<BulkImportUsers.UserRow> rows = command.users();
        if ((rows == null || rows.isEmpty())
                && command.sheet() != null
                && !command.sheet().isBlank()) {
            rows = BulkUserCsvParser.parse(command.sheet());
        }

        if (rows == null || rows.isEmpty()) {
            return new BulkUserReport(
                    BulkUserReport.EMPTY,
                    0,
                    0,
                    List.of(new BulkUserReport.Problem("", "payload", "bulk.network.payload_empty")));
        }

        // 1. Validation (All-or-None)
        List<BulkUserReport.Problem> problems = new ArrayList<>();
        Set<String> seenUsernames = new HashSet<>();
        List<String> usernamesToCheck = new ArrayList<>();

        for (BulkImportUsers.UserRow row : rows) {
            if (row.username() == null || row.username().isBlank()) {
                problems.add(new BulkUserReport.Problem("", "username", "bulk.user.username_required"));
                continue;
            }
            String norm = row.username().strip().toLowerCase(Locale.ROOT);
            if (!USERNAME_PATTERN.matcher(norm).matches()) {
                problems.add(new BulkUserReport.Problem(norm, "username", "bulk.user.username_invalid"));
            } else if (!seenUsernames.add(norm)) {
                problems.add(new BulkUserReport.Problem(norm, "username", "bulk.user.username_duplicate"));
            } else {
                usernamesToCheck.add(norm);
            }

            if (row.displayName() == null || row.displayName().isBlank()) {
                problems.add(new BulkUserReport.Problem(norm, "displayName", "bulk.user.display_name_required"));
            }

            if (row.pin() != null && !row.pin().isBlank()) {
                String pin = row.pin().strip();
                if (!PIN_PATTERN.matcher(pin).matches()) {
                    problems.add(new BulkUserReport.Problem(norm, "pin", "bulk.user.pin_invalid"));
                }
            }

            if (row.language() != null && !row.language().isBlank()) {
                String lang = row.language().strip().toLowerCase(Locale.ROOT);
                if (!Set.of("en", "si", "ta").contains(lang)) {
                    problems.add(new BulkUserReport.Problem(norm, "language", "m1.user.language_invalid"));
                }
            }

            if (row.userKind() != null && !row.userKind().isBlank()) {
                String kind = row.userKind().strip().toUpperCase(Locale.ROOT);
                if (!AppUser.KINDS.contains(kind)) {
                    problems.add(new BulkUserReport.Problem(norm, "userKind", "m1.user.kind_invalid"));
                }
            }
        }

        if (!usernamesToCheck.isEmpty()) {
            String inSql = String.join(",", Collections.nCopies(usernamesToCheck.size(), "?"));
            List<String> existing = jdbc.query(
                    "SELECT lower(username) FROM security.app_user WHERE lower(username) IN (" + inSql + ")",
                    ps -> {
                        for (int i = 0; i < usernamesToCheck.size(); i++) {
                            ps.setString(i + 1, usernamesToCheck.get(i));
                        }
                    },
                    (rs, rowNum) -> rs.getString(1));

            for (String exist : existing) {
                problems.add(new BulkUserReport.Problem(exist, "username", "bulk.user.username_duplicate"));
            }
        }

        if (!problems.isEmpty()) {
            return new BulkUserReport(BulkUserReport.REJECTED, 0, problems.size(), problems);
        }

        // 2. Execution and Identity-Provider Provisioning
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID homeEntityId = scope.entityId();

        for (BulkImportUsers.UserRow row : rows) {
            UUID userId = Ids.next();
            String norm = row.username().strip().toLowerCase(Locale.ROOT);
            String displayName = row.displayName().strip();
            String lang = row.language() == null || row.language().isBlank()
                    ? "en"
                    : row.language().strip().toLowerCase(Locale.ROOT);
            String kind = row.userKind() == null || row.userKind().isBlank()
                    ? (row.pin() != null && !row.pin().isBlank() ? AppUser.TILL : AppUser.BACK_OFFICE)
                    : row.userKind().strip().toUpperCase(Locale.ROOT);

            AppUser user = AppUser.create(userId, homeEntityId, norm, displayName, lang, kind, null);

            if (row.pin() != null && !row.pin().isBlank()) {
                String pinHash = pinHasher.hash(row.pin().strip());
                user.setPin(pinHash, now, 3);
            }

            // Provision user at Identity Provider (Keycloak)
            String subject = provider.createUser(scope, userId, homeEntityId, norm, Locale.forLanguageTag(lang));
            user.linkLogin(subject);

            if (row.password() != null && !row.password().isBlank()) {
                provider.setPassword(scope, subject, row.password().strip(), false);
                user.activateOnCredential();
            } else if (AppUser.BACK_OFFICE.equals(kind) || AppUser.BOTH.equals(kind) || AppUser.EXTERNAL.equals(kind)) {
                provider.setTemporaryPassword(scope, subject);
                user.activateOnCredential();
            } else if (user.hasTillCredential() && AppUser.PENDING.equals(user.status())) {
                if (row.pin() != null && !row.pin().isBlank()) {
                    user.activateOnCredential();
                }
            }

            users.saveAndFlush(user);

            // Audit & Events per user
            audit.record(CreateUserHandler.AUDIT_CREATED, Subject.of("user", userId), null, user.auditState(), scope);
            if (AppUser.ACTIVE.equals(user.status())) {
                audit.record(
                        ResetCredentialHandler.AUDIT_ACTIVATED,
                        Subject.of("user", userId),
                        null,
                        user.auditState(),
                        scope);
            }

            events.publish(new UserCreated(userId, userId, homeEntityId, kind, user.status()));
            if (AppUser.ACTIVE.equals(user.status())) {
                events.publish(new UserActivated(userId, userId, homeEntityId, kind, user.status()));
            }
        }

        // Summary Audit & Event
        audit.record(
                AUDIT_USERS_BULK_IMPORTED,
                Subject.of("entity", homeEntityId),
                null,
                Map.of("importedCount", rows.size()),
                scope);
        events.publish(new UsersBulkImported(homeEntityId, rows.size()));

        return new BulkUserReport(BulkUserReport.IMPORTED, rows.size(), 0, List.of());
    }
}
