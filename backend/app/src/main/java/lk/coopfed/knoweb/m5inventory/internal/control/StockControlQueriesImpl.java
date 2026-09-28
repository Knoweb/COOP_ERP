package lk.coopfed.knoweb.m5inventory.internal.control;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m5inventory.api.LossCategory;
import lk.coopfed.knoweb.m5inventory.query.CountView;
import lk.coopfed.knoweb.m5inventory.query.NegativeLotView;
import lk.coopfed.knoweb.m5inventory.query.RecipeView;
import lk.coopfed.knoweb.m5inventory.query.RepackView;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.m5inventory.query.WriteOffView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of stock control: read-only transactions that take the scope, so row-level security
 * decides which rows the caller sees (a shop session its own shop). No query names an owner.
 */
@Service
@Transactional(readOnly = true)
class StockControlQueriesImpl implements StockControlQueries {

    private static final String COUNT_COLUMNS =
            """
            select task_id, location_id, scope_kind, scope_sku_ids, scheduled_for, status, outcome, scheduled_by,
                   scheduled_at, started_by, started_at, submitted_by, submitted_at, review_value, review_band,
                   reviewed_by, reviewed_at, review_reason
              from inventory.count_task
            """;

    private static final String WRITE_OFF_COLUMNS =
            """
            select write_off_id, owner_entity_id, location_id, category, note, status, requested_by, requested_at,
                   submitted_at, document_no, value, band, witness_user_id, witnessed_at, remote_witness,
                   approver_user_id, decided_at, reject_reason
              from inventory.write_off
            """;

    private static final String REPACK_COLUMNS =
            """
            select r.repack_id, r.location_id, r.recipe_id, r.input_batch_id, r.input_sku_id, r.input_qty,
                   r.input_unit_cost, r.output_sku_id, r.output_batch_id, r.expected_output_qty, r.actual_output_qty,
                   r.variance_qty, r.output_unit_cost, r.executed_by, r.executed_at, v.reason, v.reversed_at
              from inventory.repack r
              left join inventory.repack_reversal v on v.repack_id = r.repack_id
            """;

    private final JdbcTemplate jdbc;
    private final Attachments attachments;
    private final ControlPolicy policy;
    private final PartyQueries party;

    StockControlQueriesImpl(JdbcTemplate jdbc, Attachments attachments, ControlPolicy policy, PartyQueries party) {
        this.jdbc = jdbc;
        this.attachments = attachments;
        this.policy = policy;
        this.party = party;
    }

    // ---- counts -------------------------------------------------------------------------------

    @Override
    public List<CountView> counts(UUID locationId, ScopeContext scope) {
        return jdbc.query(
                COUNT_COLUMNS + " where location_id = ? order by scheduled_at desc, task_id", this::count, locationId);
    }

    @Override
    public Optional<CountView> count(UUID taskId, ScopeContext scope) {
        return jdbc.query(COUNT_COLUMNS + " where task_id = ?", this::count, taskId).stream()
                .findFirst();
    }

    private CountView count(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("task_id", UUID.class);
        Array skus = rs.getArray("scope_sku_ids");
        return new CountView(
                id,
                rs.getObject("location_id", UUID.class),
                rs.getString("scope_kind"),
                skus == null ? List.of() : Arrays.asList((UUID[]) skus.getArray()),
                rs.getObject("scheduled_for", java.time.LocalDate.class),
                rs.getString("status"),
                rs.getString("outcome"),
                rs.getObject("scheduled_by", UUID.class),
                instant(rs, "scheduled_at"),
                rs.getObject("started_by", UUID.class),
                instant(rs, "started_at"),
                rs.getObject("submitted_by", UUID.class),
                instant(rs, "submitted_at"),
                rs.getBigDecimal("review_value"),
                (Integer) rs.getObject("review_band", Integer.class),
                rs.getObject("reviewed_by", UUID.class),
                instant(rs, "reviewed_at"),
                rs.getString("review_reason"),
                jdbc.query(
                        """
                        select batch_id, sku_id, condition, expected_qty from inventory.count_expectation
                         where task_id = ? order by sku_id, condition, batch_id
                        """,
                        (e, i) -> new CountView.Expected(
                                e.getObject("batch_id", UUID.class),
                                e.getObject("sku_id", UUID.class),
                                e.getString("condition"),
                                e.getBigDecimal("expected_qty")),
                        id),
                jdbc.query(
                        """
                        select line_no, batch_id, sku_id, condition, expected_qty, counted_qty, skip_reason,
                               variance_qty, unit_cost, variance_value, within_tolerance
                          from inventory.count_line where task_id = ? order by line_no
                        """,
                        (l, i) -> new CountView.Line(
                                l.getInt("line_no"),
                                l.getObject("batch_id", UUID.class),
                                l.getObject("sku_id", UUID.class),
                                l.getString("condition"),
                                l.getBigDecimal("expected_qty"),
                                l.getBigDecimal("counted_qty"),
                                l.getString("skip_reason"),
                                l.getBigDecimal("variance_qty"),
                                l.getBigDecimal("unit_cost"),
                                l.getBigDecimal("variance_value"),
                                l.getBoolean("within_tolerance")),
                        id));
    }

    // ---- write-offs ---------------------------------------------------------------------------

    @Override
    public List<WriteOffView> writeOffs(UUID locationId, ScopeContext scope) {
        return jdbc.query(
                WRITE_OFF_COLUMNS + " where location_id = ? order by requested_at desc, write_off_id",
                (rs, n) -> writeOff(rs, scope),
                locationId);
    }

    @Override
    public Optional<WriteOffView> writeOff(UUID writeOffId, ScopeContext scope) {
        return jdbc
                .query(WRITE_OFF_COLUMNS + " where write_off_id = ?", (rs, n) -> writeOff(rs, scope), writeOffId)
                .stream()
                .findFirst();
    }

    private WriteOffView writeOff(ResultSet rs, ScopeContext scope) throws SQLException {
        UUID id = rs.getObject("write_off_id", UUID.class);
        UUID locationId = rs.getObject("location_id", UUID.class);
        LossCategory category = LossCategory.valueOf(rs.getString("category"));
        // Whether photographs are needed is the policy's answer for the location as the reader's
        // scope sees it; a reader that cannot see the location in M1 is told the category's rule.
        boolean photosRequired = party.getLocation(locationId, scope)
                .map(location -> policy.photosRequired(category, location, scope))
                .orElse(false);
        return new WriteOffView(
                id,
                locationId,
                category.name(),
                rs.getString("note"),
                rs.getString("status"),
                rs.getObject("requested_by", UUID.class),
                instant(rs, "requested_at"),
                instant(rs, "submitted_at"),
                rs.getString("document_no"),
                rs.getBigDecimal("value"),
                (Integer) rs.getObject("band", Integer.class),
                rs.getObject("witness_user_id", UUID.class),
                instant(rs, "witnessed_at"),
                rs.getBoolean("remote_witness"),
                rs.getObject("approver_user_id", UUID.class),
                instant(rs, "decided_at"),
                rs.getString("reject_reason"),
                photosRequired,
                jdbc.query(
                        """
                        select line_no, batch_id, sku_id, condition, qty from inventory.write_off_line
                         where write_off_id = ? order by line_no
                        """,
                        (l, i) -> new WriteOffView.Line(
                                l.getInt("line_no"),
                                l.getObject("batch_id", UUID.class),
                                l.getObject("sku_id", UUID.class),
                                l.getString("condition"),
                                l.getBigDecimal("qty")),
                        id),
                jdbc
                        .queryForList(
                                "select attachment_id from inventory.write_off_photo where write_off_id = ?"
                                        + " order by added_at, attachment_id",
                                UUID.class,
                                id)
                        .stream()
                        .map(a ->
                                new WriteOffView.Photo(a, attachments.status(a).orElse("PENDING")))
                        .toList());
    }

    // ---- recipes and repacks ------------------------------------------------------------------

    @Override
    public List<RecipeView> recipes(ScopeContext scope) {
        return jdbc.query(
                """
                select recipe_id, name, input_sku_id, input_qty, output_sku_id, output_qty, expected_loss_pct, status
                  from inventory.repack_recipe
                 order by status, lower(name), recipe_id
                """,
                (rs, n) -> new RecipeView(
                        rs.getObject("recipe_id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("input_sku_id", UUID.class),
                        rs.getBigDecimal("input_qty"),
                        rs.getObject("output_sku_id", UUID.class),
                        rs.getBigDecimal("output_qty"),
                        rs.getBigDecimal("expected_loss_pct"),
                        rs.getString("status")));
    }

    @Override
    public List<RepackView> repacks(UUID locationId, ScopeContext scope) {
        return jdbc.query(
                REPACK_COLUMNS + " where r.location_id = ? order by r.executed_at desc, r.repack_id",
                StockControlQueriesImpl::repack,
                locationId);
    }

    @Override
    public Optional<RepackView> repack(UUID repackId, ScopeContext scope) {
        return jdbc.query(REPACK_COLUMNS + " where r.repack_id = ?", StockControlQueriesImpl::repack, repackId).stream()
                .findFirst();
    }

    private static RepackView repack(ResultSet rs, int n) throws SQLException {
        Instant reversedAt = instant(rs, "reversed_at");
        return new RepackView(
                rs.getObject("repack_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("recipe_id", UUID.class),
                rs.getObject("input_batch_id", UUID.class),
                rs.getObject("input_sku_id", UUID.class),
                rs.getBigDecimal("input_qty"),
                rs.getBigDecimal("input_unit_cost"),
                rs.getObject("output_sku_id", UUID.class),
                rs.getObject("output_batch_id", UUID.class),
                rs.getBigDecimal("expected_output_qty"),
                rs.getBigDecimal("actual_output_qty"),
                rs.getBigDecimal("variance_qty"),
                rs.getBigDecimal("output_unit_cost"),
                reversedAt == null ? "EXECUTED" : "REVERSED",
                rs.getObject("executed_by", UUID.class),
                instant(rs, "executed_at"),
                rs.getString("reason"),
                reversedAt);
    }

    // ---- negative lots ------------------------------------------------------------------------

    private static final String NEGATIVE_LOTS =
            """
            select stock_lot_id, location_id, sku_id, batch_id, condition, qty_on_hand, negative_since,
                   negative_acknowledged_at
              from inventory.stock_lot
             where qty_on_hand < 0
            """;

    @Override
    public List<NegativeLotView> negativeLots(UUID locationId, ScopeContext scope) {
        return jdbc.query(
                NEGATIVE_LOTS + " and location_id = ? order by negative_since nulls last, stock_lot_id",
                StockControlQueriesImpl::negativeLot,
                locationId);
    }

    @Override
    public Optional<NegativeLotView> negativeLot(UUID stockLotId, ScopeContext scope) {
        return jdbc
                .query(NEGATIVE_LOTS + " and stock_lot_id = ?", StockControlQueriesImpl::negativeLot, stockLotId)
                .stream()
                .findFirst();
    }

    private static NegativeLotView negativeLot(ResultSet rs, int n) throws SQLException {
        Instant since = instant(rs, "negative_since");
        Instant acknowledged = instant(rs, "negative_acknowledged_at");
        return new NegativeLotView(
                rs.getObject("stock_lot_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getString("condition"),
                rs.getBigDecimal("qty_on_hand"),
                since,
                // An acknowledgement of an earlier time below zero does not count.
                acknowledged != null && since != null && acknowledged.isBefore(since) ? null : acknowledged);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
