package lk.coopfed.knoweb.m6pos.internal.query;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m6pos.query.PosQueries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PosQueries under row-level security: no query names an owner.
 *
 * <p>Paging (wave 2, M6-08): newest first by (time, id), so a sale arriving while a person pages
 * never shifts a page (offset paging would); the cursor is the last row's time and id, encoded so
 * that a client treats it as opaque. The business day is the time between two midnights in the
 * business time zone, which {@code receipt_by_location (location_id, issued_at)} serves; the
 * lines and tenders of a page are two {@code = any(?)} queries, not two per receipt.
 *
 * <p>No nested types here: the architecture rule for published {@code ..query..} packages also
 * matches this {@code internal.query} package, so the helpers are methods.
 */
@Service
@Transactional(readOnly = true)
class PosQueriesImpl implements PosQueries {

    /** The longest page, a configuration item (AGENTS.md: no limit is hard-coded). */
    static final String CONFIG_MAX_PAGE = "pos.list.max_page";

    static final int DEFAULT_PAGE = 50;

    private static final String RECEIPT_COLUMNS =
            """
            select document_id, location_id, device_id, session_id, doc_number_display, issued_at, business_date,
                   gross_amount, flags, till_position_id, net_amount, tax_amount
              from pos.receipt
            """;

    /**
     * Every session opened and every close whose open never arrived (M6-10): the full outer join of
     * the two tables, each filtered by the location first. {@code at} is the open, or the close for
     * an orphan close; it orders and pages the list.
     */
    private static final String SESSIONS =
            """
            select * from (
                select coalesce(s.session_id, c.session_id) as session_id,
                       coalesce(s.location_id, c.location_id) as location_id,
                       s.till_position_id, s.business_date, s.opened_at, s.float_amount,
                       c.closed_at, c.counted_cash, c.expected_cash, c.variance,
                       coalesce(s.opened_at, c.closed_at) as at
                  from (select * from pos.till_session where %1$s) s
                  full join (select * from pos.till_session_close where %1$s) c on c.session_id = s.session_id
            ) x
            """;

    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;
    private final ZoneId businessZone;

    PosQueriesImpl(
            JdbcTemplate jdbc, ConfigRegistry config, @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jdbc = jdbc;
        this.config = config;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Override
    public Page<ReceiptView> receipts(ReceiptFilter filter, ScopeContext scope) {
        int limit = pageSize(filter.limit(), scope);
        StringBuilder sql = new StringBuilder(RECEIPT_COLUMNS).append(" where location_id = ?");
        List<Object> args = new ArrayList<>(List.of(filter.locationId()));
        if (filter.businessDate() != null) {
            sql.append(" and issued_at >= ? and issued_at < ?");
            args.add(startOf(filter.businessDate()));
            args.add(startOf(filter.businessDate().plusDays(1)));
        }
        if (filter.flaggedOnly()) {
            sql.append(" and cardinality(flags) > 0");
        }
        Map.Entry<Instant, UUID> after = decodeCursor(filter.cursor());
        if (after != null) {
            sql.append(" and (issued_at, document_id) < (?, ?)");
            args.add(after.getKey().atOffset(ZoneOffset.UTC));
            args.add(after.getValue());
        }
        sql.append(" order by issued_at desc, document_id desc limit ?");
        args.add(limit + 1);

        List<ReceiptView> headers = jdbc.query(sql.toString(), PosQueriesImpl::header, args.toArray());
        List<ReceiptView> page = headers.subList(0, Math.min(limit, headers.size()));
        String next = headers.size() > limit
                ? encodeCursor(page.getLast().issuedAt(), page.getLast().documentId())
                : null;
        return new Page<>(withLinesAndTenders(page), next);
    }

    @Override
    public Optional<ReceiptView> receipt(UUID documentId, ScopeContext scope) {
        List<ReceiptView> found =
                jdbc.query(RECEIPT_COLUMNS + " where document_id = ?", PosQueriesImpl::header, documentId);
        return withLinesAndTenders(found).stream().findFirst();
    }

    @Override
    public Page<SessionView> sessions(SessionFilter filter, ScopeContext scope) {
        int limit = pageSize(filter.limit(), scope);
        StringBuilder sql = new StringBuilder(SESSIONS.formatted("location_id = ?")).append(" where true");
        List<Object> args = new ArrayList<>(List.of(filter.locationId(), filter.locationId()));
        if (filter.businessDate() != null) {
            sql.append(" and at >= ? and at < ?");
            args.add(startOf(filter.businessDate()));
            args.add(startOf(filter.businessDate().plusDays(1)));
        }
        Map.Entry<Instant, UUID> after = decodeCursor(filter.cursor());
        if (after != null) {
            sql.append(" and (at, session_id) < (?, ?)");
            args.add(after.getKey().atOffset(ZoneOffset.UTC));
            args.add(after.getValue());
        }
        sql.append(" order by at desc, session_id desc limit ?");
        args.add(limit + 1);

        List<SessionView> rows = jdbc.query(sql.toString(), PosQueriesImpl::session, args.toArray());
        List<SessionView> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = rows.size() > limit
                ? encodeCursor(at(page.getLast()), page.getLast().sessionId())
                : null;
        return new Page<>(List.copyOf(page), next);
    }

    @Override
    public Optional<SessionView> session(UUID sessionId, ScopeContext scope) {
        return jdbc.query(SESSIONS.formatted("session_id = ?"), PosQueriesImpl::session, sessionId, sessionId).stream()
                .findFirst();
    }

    private int pageSize(Integer requested, ScopeContext scope) {
        int max = Math.max(1, config.getInt(CONFIG_MAX_PAGE, scope, 200));
        int wanted = requested == null || requested < 1 ? DEFAULT_PAGE : requested;
        return Math.min(wanted, max);
    }

    private OffsetDateTime startOf(LocalDate day) {
        return day.atStartOfDay(businessZone).toOffsetDateTime();
    }

    /** A receipt's own columns; its lines and tenders are read for the whole page at once. */
    private static ReceiptView header(ResultSet rs, int n) throws SQLException {
        return new ReceiptView(
                rs.getObject("document_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("doc_number_display"),
                instant(rs, "issued_at"),
                rs.getObject("business_date", LocalDate.class),
                rs.getBigDecimal("gross_amount"),
                texts(rs.getArray("flags")),
                List.of(),
                rs.getObject("till_position_id", UUID.class),
                rs.getBigDecimal("net_amount"),
                rs.getBigDecimal("tax_amount"),
                List.of());
    }

    private List<ReceiptView> withLinesAndTenders(List<ReceiptView> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        Object ids = headers.stream().map(ReceiptView::documentId).toArray(UUID[]::new);
        Map<UUID, List<ReceiptView.Line>> lines = new LinkedHashMap<>();
        jdbc.query(
                """
                select document_id, line_no, sku_id, batch_id, qty, unit_price, line_total
                  from pos.receipt_line
                 where document_id = any(?)
                 order by document_id, line_no
                """,
                rs -> {
                    lines.computeIfAbsent(rs.getObject("document_id", UUID.class), id -> new ArrayList<>())
                            .add(new ReceiptView.Line(
                                    rs.getInt("line_no"),
                                    rs.getObject("sku_id", UUID.class),
                                    rs.getObject("batch_id", UUID.class),
                                    rs.getBigDecimal("qty"),
                                    rs.getBigDecimal("unit_price"),
                                    rs.getBigDecimal("line_total")));
                },
                ids);
        Map<UUID, List<ReceiptView.Tender>> tenders = new LinkedHashMap<>();
        jdbc.query(
                """
                select document_id, seq, kind, amount
                  from pos.receipt_tender
                 where document_id = any(?)
                 order by document_id, seq
                """,
                rs -> {
                    tenders.computeIfAbsent(rs.getObject("document_id", UUID.class), id -> new ArrayList<>())
                            .add(new ReceiptView.Tender(
                                    rs.getInt("seq"), rs.getString("kind"), rs.getBigDecimal("amount")));
                },
                ids);
        return headers.stream()
                .map(h -> new ReceiptView(
                        h.documentId(),
                        h.locationId(),
                        h.deviceId(),
                        h.sessionId(),
                        h.docNumberDisplay(),
                        h.issuedAt(),
                        h.businessDate(),
                        h.grossAmount(),
                        h.flags(),
                        lines.getOrDefault(h.documentId(), List.of()),
                        h.tillPositionId(),
                        h.netAmount(),
                        h.taxAmount(),
                        tenders.getOrDefault(h.documentId(), List.of())))
                .toList();
    }

    private static SessionView session(ResultSet rs, int n) throws SQLException {
        return new SessionView(
                rs.getObject("session_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("till_position_id", UUID.class),
                rs.getObject("business_date", LocalDate.class),
                instant(rs, "opened_at"),
                rs.getBigDecimal("float_amount"),
                instant(rs, "closed_at"),
                rs.getBigDecimal("counted_cash"),
                rs.getBigDecimal("expected_cash"),
                rs.getBigDecimal("variance"));
    }

    /** The time a session is listed by: its open, or its close when the open never arrived. */
    private static Instant at(SessionView session) {
        return session.openedAt() != null ? session.openedAt() : session.closedAt();
    }

    /**
     * Where a page ended: the last row's time and id, as base64url text so that a client never
     * builds one.
     */
    static String encodeCursor(Instant at, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((at + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** The time and id a cursor names, or null for none; one that cannot be read is a malformed request (400). */
    static Map.Entry<Instant, UUID> decodeCursor(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            int bar = decoded.indexOf('|');
            if (bar < 0) {
                throw new ProblemException("request.malformed");
            }
            return new AbstractMap.SimpleImmutableEntry<>(
                    Instant.parse(decoded.substring(0, bar)), UUID.fromString(decoded.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new ProblemException("request.malformed");
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
