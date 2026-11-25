package lk.coopfed.knoweb.m4trading.internal.order;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.STRANGER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.today;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.event.PublishedEventListener;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.IssueDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.OrderAdjusted;
import lk.coopfed.knoweb.m4trading.api.RecordOrderAdjustment;
import lk.coopfed.knoweb.m4trading.api.SubmitOrder;
import lk.coopfed.knoweb.m4trading.internal.delivery.DispatchDeliveryNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.delivery.IssueDeliveryNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderAdjustmentView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PRC-10 / FR-PRC-010: Order adjustments (delivery fee and special discount).
 *
 * <p>Proves that a permitted seller user can add adjustments to an accepted order,
 * guards against unpermitted or invalid calls, and proves adjustments flow to invoice lines.
 */
@Import(TradingFlow.class)
class OrderAdjustmentPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER_ROLE = UUID.fromString("0190f400-0000-7000-8000-000000000091");
    private static final UUID LIMITED_USER = UUID.fromString("0190f400-0000-7000-8000-000000000092");
    private static final UUID LIMITED_ROLE = UUID.fromString("0190f400-0000-7000-8000-000000000093");
    private static final UUID ADJUSTER_USER = UUID.fromString("0190f400-0000-7000-8000-000000000095");

    private static ScopeContext seller() {
        return ScopeContext.dev(ADJUSTER_USER, SELLER, null);
    }

    @Autowired(required = false)
    private List<PublishedEventListener> cacheListeners;

    private void invalidatePermissionCache() {
        if (cacheListeners != null) {
            JsonNode emptyPayload = new ObjectMapper().createObjectNode();
            for (PublishedEventListener listener : cacheListeners) {
                listener.published("role.changed.v1", emptyPayload);
            }
        }
    }

    @Autowired
    TradingFlow flow;

    @Autowired
    CreateOrderHandler createOrder;

    @Autowired
    SubmitOrderHandler submitOrder;

    @Autowired
    RecordOrderAdjustmentHandler adjust;

    @Autowired
    OrderQueries orders;

    @Autowired
    IssueDeliveryNoteHandler issueNote;

    @Autowired
    DispatchDeliveryNoteHandler dispatchNote;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

    @Autowired
    IssueInvoiceHandler issueInvoice;

    @Autowired
    InvoiceQueries invoices;

    @Autowired
    DeliveryQueries deliveries;

    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
        setupSellerPermissions();
        kernel.reset();
    }

    @AfterEach
    void clean() {
        cleanSecurity();
        TradingFixture.clean(superuserJdbc());
    }

    private void setupSellerPermissions() {
        JdbcTemplate admin = superuserJdbc();
        // Insert seller user
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                        + " values (?, ?, ?, 'Seller User', 'BACK_OFFICE', 'ACTIVE')"
                        + " on conflict (user_id) do nothing",
                ADJUSTER_USER,
                SELLER,
                "u-seller-" + ADJUSTER_USER);
        createdUsers.add(ADJUSTER_USER);

        // Role with ord.order.adjust, prc.adjust.delivery, and prc.adjust.special
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, 'Seller Adjustment Manager', false, 'OWN', 'ACTIVE')"
                        + " on conflict (role_id) do nothing",
                SELLER_ROLE,
                SELLER);
        createdRoles.add(SELLER_ROLE);

        for (String perm : List.of("ord.order.adjust", "prc.adjust.delivery", "prc.adjust.special")) {
            admin.update(
                    "insert into security.role_permission (role_id, permission_code) values (?, ?)"
                            + " on conflict do nothing",
                    SELLER_ROLE,
                    perm);
        }

        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id)"
                        + " values (?, ?, ?, null) on conflict do nothing",
                ADJUSTER_USER,
                SELLER_ROLE,
                SELLER);
        invalidatePermissionCache();
    }

    private void cleanSecurity() {
        JdbcTemplate admin = superuserJdbc();
        for (UUID u : createdUsers) {
            admin.update("delete from security.user_role where user_id = ?", u);
            admin.update("delete from security.app_user where user_id = ?", u);
        }
        for (UUID r : createdRoles) {
            admin.update("delete from security.role_permission where role_id = ?", r);
            admin.update("delete from security.role where role_id = ?", r);
        }
        createdUsers.clear();
        createdRoles.clear();
        invalidatePermissionCache();
    }

    @Test
    void permittedSellerRecordsDeliveryFeeOnAcceptedOrder() {
        OrderView order = flow.acceptedOrder();
        kernel.reset();

        UUID adjId = adjust.handle(
                new RecordOrderAdjustment(
                        order.orderId(), "DELIVERY_FEE", new BigDecimal("500.00"), "Express route delivery fee"),
                seller());

        assertThat(adjId).isNotNull();

        // 1. Audit check
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("ORDER_ADJUSTED");

        // 2. Domain event check
        assertThat(events(OrderAdjusted.class)).singleElement().satisfies(event -> {
            assertThat(event.adjustmentId()).isEqualTo(adjId);
            assertThat(event.orderId()).isEqualTo(order.orderId());
            assertThat(event.sellerEntityId()).isEqualTo(SELLER);
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.adjustmentType()).isEqualTo("DELIVERY_FEE");
            assertThat(event.kind()).isEqualTo("DELIVERY_FEE");
            assertThat(event.amount()).isEqualByComparingTo("500.00");
            assertThat(event.description()).isEqualTo("Express route delivery fee");
        });

        // 3. Database record
        List<OrderAdjustmentView> sellerList = orders.listAdjustments(order.orderId(), seller());
        assertThat(sellerList).hasSize(1);
        OrderAdjustmentView adj = sellerList.get(0);
        assertThat(adj.adjustmentId()).isEqualTo(adjId);
        assertThat(adj.orderId()).isEqualTo(order.orderId());
        assertThat(adj.adjustmentType()).isEqualTo("DELIVERY_FEE");
        assertThat(adj.kind()).isEqualTo("DELIVERY_FEE");
        assertThat(adj.amount()).isEqualByComparingTo("500.00");
        assertThat(adj.description()).isEqualTo("Express route delivery fee");
        assertThat(adj.recordedBy()).isEqualTo(ADJUSTER_USER);
        assertThat(adj.invoiceDocumentId()).isNull();

        // 4. Counterparty (buyer) can also view the adjustment through PARTY policy
        List<OrderAdjustmentView> buyerList = orders.listAdjustments(order.orderId(), buyer());
        assertThat(buyerList).hasSize(1);
        assertThat(buyerList.get(0).adjustmentId()).isEqualTo(adjId);
    }

    @Test
    void permittedSellerRecordsSpecialDiscountOnAcceptedOrder() {
        OrderView order = flow.acceptedOrder();
        kernel.reset();

        UUID adjId = adjust.handle(
                new RecordOrderAdjustment(
                        order.orderId(), "SPECIAL_DISC", new BigDecimal("50.00"), "Commercial goodwill"),
                seller());

        assertThat(adjId).isNotNull();

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("ORDER_ADJUSTED");

        assertThat(events(OrderAdjusted.class)).singleElement().satisfies(event -> {
            assertThat(event.adjustmentId()).isEqualTo(adjId);
            assertThat(event.orderId()).isEqualTo(order.orderId());
            assertThat(event.sellerEntityId()).isEqualTo(SELLER);
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.adjustmentType()).isEqualTo("SPECIAL_DISC");
            assertThat(event.kind()).isEqualTo("SPECIAL_DISCOUNT");
            assertThat(event.amount()).isEqualByComparingTo("50.00");
        });

        List<OrderAdjustmentView> adjs = orders.listAdjustments(order.orderId(), seller());
        assertThat(adjs).hasSize(1);
        assertThat(adjs.get(0).adjustmentType()).isEqualTo("SPECIAL_DISC");
        assertThat(adjs.get(0).kind()).isEqualTo("SPECIAL_DISCOUNT");
    }

    @Test
    void deliveryFeeFlowsToInvoiceAsAChargeLine() {
        // Step 1: Accepted order (10 rice @ 120 = 1200, 4 dhal @ 80 = 320 -> total 1520.00)
        OrderView order = flow.acceptedOrder();

        // Step 2: Record delivery fee of 500.00
        UUID adjId = adjust.handle(
                new RecordOrderAdjustment(
                        order.orderId(), "DELIVERY_FEE", new BigDecimal("500.00"), "Delivery fee to shop"),
                seller());

        // Step 3: Dispatch delivery note to SHOP
        UUID noteId = flow.draftNote(order, SHOP);
        issueNote.handle(new IssueDeliveryNote(noteId), seller());
        dispatchNote.handle(
                new lk.coopfed.knoweb.m4trading.api.DispatchDeliveryNote(noteId, null, null, null), seller());

        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();

        // Step 4: Buyer captures and confirms GRN in full (10 rice, 4 dhal)
        UUID grnId =
                capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "10"), line(DHAL, "4"))), buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());

        // Step 5: Issue invoice as seller
        kernel.reset();
        UUID invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());

        InvoiceView invoice = invoices.getInvoice(invoiceId, buyer()).orElseThrow();

        // Goods net: 10 * 120 + 4 * 80 = 1520.00
        // Delivery fee: +500.00
        // Net amount should be 1520.00 + 500.00 = 2020.00
        // VAT: rice 18% of 1200.00 = 216.00; dhal EXEMPT 0% = 0.00; delivery fee 0% = 0.00
        // Gross amount: 2020.00 + 216.00 = 2236.00
        assertThat(invoice.netAmount()).isEqualByComparingTo("2020.00");
        assertThat(invoice.taxAmount()).isEqualByComparingTo("216.00");
        assertThat(invoice.grossAmount()).isEqualByComparingTo("2236.00");

        // Verify lines: 2 goods lines + 1 adjustment line
        assertThat(invoice.lines()).hasSize(3);
        assertThat(invoice.lines())
                .filteredOn(l -> l.skuId() == null)
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.lineTotal()).isEqualByComparingTo("500.00");
                    assertThat(line.taxRatePercent()).isEqualByComparingTo("0.00");
                    assertThat(line.taxAmount()).isEqualByComparingTo("0.00");
                });

        // Verify adjustment row was linked to this invoice
        List<OrderAdjustmentView> adjs = orders.listAdjustments(order.orderId(), seller());
        assertThat(adjs).hasSize(1);
        assertThat(adjs.get(0).invoiceDocumentId()).isEqualTo(invoiceId);
    }

    @Test
    void specialDiscountFlowsToInvoiceAsAReductionLine() {
        OrderView order = flow.acceptedOrder();

        UUID adjId = adjust.handle(
                new RecordOrderAdjustment(
                        order.orderId(), "SPECIAL_DISC", new BigDecimal("60.00"), "Loyalty concession"),
                seller());

        UUID noteId = flow.draftNote(order, SHOP);
        issueNote.handle(new IssueDeliveryNote(noteId), seller());
        dispatchNote.handle(
                new lk.coopfed.knoweb.m4trading.api.DispatchDeliveryNote(noteId, null, null, null), seller());

        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();

        UUID grnId =
                capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "10"), line(DHAL, "4"))), buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());

        UUID invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());

        InvoiceView invoice = invoices.getInvoice(invoiceId, buyer()).orElseThrow();

        // Goods net: 1520.00
        // Special discount: -60.00
        // Net amount: 1520.00 - 60.00 = 1460.00
        // VAT: 216.00
        // Gross amount: 1460.00 + 216.00 = 1676.00
        assertThat(invoice.netAmount()).isEqualByComparingTo("1460.00");
        assertThat(invoice.taxAmount()).isEqualByComparingTo("216.00");
        assertThat(invoice.grossAmount()).isEqualByComparingTo("1676.00");

        assertThat(invoice.lines())
                .filteredOn(l -> l.skuId() == null)
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.lineTotal()).isEqualByComparingTo("-60.00");
                    assertThat(line.taxRatePercent()).isEqualByComparingTo("0.00");
                });

        assertThat(orders.listAdjustments(order.orderId(), seller()).get(0).invoiceDocumentId())
                .isEqualTo(invoiceId);
    }

    @Test
    void bothDeliveryFeeAndDiscountApplyToTheSameInvoice() {
        OrderView order = flow.acceptedOrder();

        adjust.handle(
                new RecordOrderAdjustment(order.orderId(), "DELIVERY_FEE", new BigDecimal("200.00"), "Logistics"),
                seller());
        adjust.handle(
                new RecordOrderAdjustment(order.orderId(), "SPECIAL_DISC", new BigDecimal("50.00"), "Promo"), seller());

        UUID noteId = flow.draftNote(order, SHOP);
        issueNote.handle(new IssueDeliveryNote(noteId), seller());
        dispatchNote.handle(
                new lk.coopfed.knoweb.m4trading.api.DispatchDeliveryNote(noteId, null, null, null), seller());

        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();
        UUID grnId =
                capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "10"), line(DHAL, "4"))), buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());

        UUID invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        InvoiceView invoice = invoices.getInvoice(invoiceId, buyer()).orElseThrow();

        // 1520.00 + 200.00 - 50.00 = 1670.00 net
        assertThat(invoice.netAmount()).isEqualByComparingTo("1670.00");
        assertThat(invoice.grossAmount()).isEqualByComparingTo("1886.00"); // 1670 + 216

        List<OrderAdjustmentView> adjs = orders.listAdjustments(order.orderId(), seller());
        assertThat(adjs).hasSize(2).allSatisfy(a -> assertThat(a.invoiceDocumentId())
                .isEqualTo(invoiceId));
    }

    @Test
    void userWithoutSpecificAdjustmentPermissionIsRefused() {
        OrderView order = flow.acceptedOrder();

        // Create limited user holding only ord.order.adjust, but lacking prc.adjust.delivery
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                        + " values (?, ?, 'u-limited', 'Limited User', 'BACK_OFFICE', 'ACTIVE')",
                LIMITED_USER,
                SELLER);
        createdUsers.add(LIMITED_USER);

        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, 'Limited Adjuster', false, 'OWN', 'ACTIVE')",
                LIMITED_ROLE,
                SELLER);
        createdRoles.add(LIMITED_ROLE);

        admin.update(
                "insert into security.role_permission (role_id, permission_code) values (?, 'ord.order.adjust')",
                LIMITED_ROLE);

        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id)"
                        + " values (?, ?, ?, null)",
                LIMITED_USER,
                LIMITED_ROLE,
                SELLER);
        invalidatePermissionCache();

        ScopeContext limitedCaller = ScopeContext.dev(LIMITED_USER, SELLER, null);

        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(order.orderId(), "DELIVERY_FEE", new BigDecimal("500.00"), null),
                        limitedCaller),
                "m4.adjustment.permission_denied");
    }

    @Test
    void nonSellerCallersAreRefused() {
        OrderView order = flow.acceptedOrder();

        // Buyer cannot adjust seller's order
        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(order.orderId(), "DELIVERY_FEE", new BigDecimal("500.00"), null),
                        buyer()),
                "m4.order.not_seller");

        // Stranger cannot adjust
        ScopeContext stranger = ScopeContext.dev(UUID.randomUUID(), STRANGER, null);
        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(order.orderId(), "DELIVERY_FEE", new BigDecimal("500.00"), null),
                        stranger),
                "m4.order.not_found");
    }

    @Test
    void orderInDraftOrSubmittedStatusIsNotAdjustable() {
        // Draft order
        UUID draftOrderId = createOrder.handle(
                new CreateOrder(
                        SELLER,
                        today().plusDays(3),
                        null,
                        List.of(new CreateOrder.Line(RICE, "EA", new BigDecimal("10")))),
                buyer());

        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(draftOrderId, "DELIVERY_FEE", new BigDecimal("500.00"), null),
                        seller()),
                "m4.order.not_adjustable");

        // Submitted (not accepted) order
        submitOrder.handle(new SubmitOrder(draftOrderId), buyer());
        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(draftOrderId, "DELIVERY_FEE", new BigDecimal("500.00"), null),
                        seller()),
                "m4.order.not_adjustable");
    }

    @Test
    void unknownAdjustmentTypeIsRefused() {
        OrderView order = flow.acceptedOrder();

        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(order.orderId(), "NON_EXISTENT_TYPE", new BigDecimal("100.00"), null),
                        seller()),
                "m4.adjustment.type_unknown");
    }

    @Test
    void invalidAmountIsRefused() {
        OrderView order = flow.acceptedOrder();

        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(order.orderId(), "DELIVERY_FEE", BigDecimal.ZERO, null), seller()),
                "m4.adjustment.amount_invalid");

        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(order.orderId(), "DELIVERY_FEE", new BigDecimal("-50.00"), null),
                        seller()),
                "m4.adjustment.amount_invalid");
    }

    @Test
    void exceedsMaxAmountIsRefused() {
        OrderView order = flow.acceptedOrder();

        // DELIVERY_FEE has max_amount = 25000.00
        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(order.orderId(), "DELIVERY_FEE", new BigDecimal("25000.01"), null),
                        seller()),
                "m4.adjustment.exceeds_max_amount");
    }

    @Test
    void exceedsMaxPercentIsRefused() {
        OrderView order = flow.acceptedOrder();
        // SPECIAL_DISC has max_percent = 5.00
        // Order total is 1520.00. 5% of 1520 = 76.00.
        // 80.00 > 76.00 -> should throw exceeds_max_percent.
        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(order.orderId(), "SPECIAL_DISC", new BigDecimal("80.00"), null),
                        seller()),
                "m4.adjustment.exceeds_max_percent");

        // 75.00 <= 76.00 -> should succeed
        UUID adjId = adjust.handle(
                new RecordOrderAdjustment(order.orderId(), "SPECIAL_DISC", new BigDecimal("75.00"), null), seller());
        assertThat(adjId).isNotNull();
    }

    @Test
    void duplicateAdjustmentTypeOnSameOrderIsRefused() {
        OrderView order = flow.acceptedOrder();

        adjust.handle(
                new RecordOrderAdjustment(order.orderId(), "DELIVERY_FEE", new BigDecimal("500.00"), "First fee"),
                seller());

        refused(
                () -> adjust.handle(
                        new RecordOrderAdjustment(
                                order.orderId(), "DELIVERY_FEE", new BigDecimal("300.00"), "Second fee"),
                        seller()),
                "m4.adjustment.already_recorded");
    }

    private static CaptureGrn.Line line(UUID sku, String received) {
        return new CaptureGrn.Line(sku, "EA", new BigDecimal(received), BigDecimal.ZERO, null, null, null, null);
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
