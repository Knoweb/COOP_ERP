package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link ChangeLog} on {@code kernel.change_log} through {@code kernel.change_log_append}
 * (kernel V0080): one call per target location, each bumping that location's version by one and
 * writing the changed rows under it. The function runs as the migrator, so the rows of every
 * target are written whatever the producer's entity; it is the only way into the table.
 */
@Component
public class JdbcChangeLog implements ChangeLog {

    /** A snapshot table's name: it travels in an array literal, so nothing else is let through. */
    private static final Pattern TABLE = Pattern.compile("[a-z][a-z0-9_]{0,47}");

    private final JdbcTemplate jdbc;

    JdbcChangeLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Long> append(
            Collection<Target> targets, List<Change> changes, LocalDate applyFrom, boolean urgent, ScopeContext ctx) {
        // Not @Transactional on purpose: the producer's transaction is the one, with the scope its
        // handler set; an annotation here would have the scope customizer set it again.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ChangeLog.append runs inside the publishing transaction");
        }
        if (changes == null || changes.isEmpty() || targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("A change-log entry names at least one location and one change");
        }
        for (Change change : changes) {
            if (!TABLE.matcher(change.table()).matches()) {
                throw new IllegalArgumentException(
                        "A snapshot table is lower case with underscores: " + change.table());
            }
        }
        String tables = changes.stream().map(Change::table).collect(Collectors.joining(",", "{", "}"));
        String rows = changes.stream().map(c -> c.rowId().toString()).collect(Collectors.joining(",", "{", "}"));
        String ops = changes.stream().map(c -> c.op().name()).collect(Collectors.joining(",", "{", "}"));
        Map<UUID, Long> versions = new LinkedHashMap<>();
        for (Target target : targets) {
            Long version = jdbc.queryForObject(
                    "select kernel.change_log_append(?, ?, cast(? as text[]), cast(? as uuid[]), cast(? as text[]), ?, ?)",
                    Long.class,
                    target.ownerEntityId(),
                    target.locationId(),
                    tables,
                    rows,
                    ops,
                    applyFrom == null ? null : Date.valueOf(applyFrom),
                    urgent);
            versions.put(target.locationId(), version);
        }
        return versions;
    }
}
