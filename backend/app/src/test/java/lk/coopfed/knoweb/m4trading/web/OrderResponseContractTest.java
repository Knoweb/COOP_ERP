package lk.coopfed.knoweb.m4trading.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.m4trading.web.generated.OrderResponse;
import lk.coopfed.knoweb.m4trading.web.generated.OrderStatus;
import org.junit.jupiter.api.Test;

/**
 * Contract test for OrderResponse verifying that target branch identity and acting user ID
 * are properly mapped and exposed to clients.
 */
class OrderResponseContractTest {

    @Test
    void toResponsePreservesBothTargetBranchIdentityAndActingUser() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID actingUserId = UUID.randomUUID();

        OrderView view = new OrderView(
                orderId,
                "ORD-0000001",
                "DRAFT",
                relationshipId,
                buyer,
                seller,
                LocalDate.now().plusDays(2),
                null,
                null,
                Instant.now(),
                null,
                new BigDecimal("5000.00"),
                "On-behalf replenishment for manual branch",
                List.of(new OrderView.OrderLineView(
                        UUID.randomUUID(),
                        1,
                        UUID.randomUUID(),
                        "EA",
                        new BigDecimal("10"),
                        BigDecimal.ZERO,
                        new BigDecimal("500.00"),
                        null,
                        null,
                        null,
                        null)),
                branchId,
                new OrderView.DeliveryPoint("MB1", "Manual Branch", "අතින් ශාඛාව", "கையேடு கிளை", "123 Main St"),
                1,
                null,
                null,
                actingUserId);

        OrderResponse response = OrderController.toResponse(view);

        assertThat(response.getOrderId()).isEqualTo(orderId);
        assertThat(response.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(response.getDeliverToLocationId()).isEqualTo(branchId);
        assertThat(response.getOperatorUserId()).isEqualTo(actingUserId);
        assertThat(response.getDeliverTo()).isNotNull();
        assertThat(response.getDeliverTo().getCode()).isEqualTo("MB1");
        assertThat(response.getDeliverTo().getNameEn()).isEqualTo("Manual Branch");
        assertThat(response.getNotes()).isEqualTo("On-behalf replenishment for manual branch");
    }
}
