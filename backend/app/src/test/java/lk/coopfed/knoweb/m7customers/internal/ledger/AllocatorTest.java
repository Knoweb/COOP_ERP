package lk.coopfed.knoweb.m7customers.internal.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m7customers.internal.ledger.Allocator.Allocation;
import lk.coopfed.knoweb.m7customers.internal.ledger.Allocator.OpenCharge;
import org.junit.jupiter.api.Test;

/** Allocator (27A section 6.3): oldest first and specific, partial and over-payment. */
class AllocatorTest {

    private static final UUID OLD = UUID.randomUUID();
    private static final UUID SAME_DAY_LATER = UUID.randomUUID();
    private static final UUID NEW = UUID.randomUUID();

    private static final UUID NEW_RECEIPT = UUID.randomUUID();

    private static final List<OpenCharge> OPEN = List.of(
            new OpenCharge(
                    NEW, NEW_RECEIPT, LocalDate.of(2026, 9, 5), Instant.parse("2026-09-05T04:00:00Z"), money("700")),
            new OpenCharge(
                    SAME_DAY_LATER,
                    UUID.randomUUID(),
                    LocalDate.of(2026, 9, 1),
                    Instant.parse("2026-09-01T09:00:00Z"),
                    money("300")),
            new OpenCharge(
                    OLD,
                    UUID.randomUUID(),
                    LocalDate.of(2026, 9, 1),
                    Instant.parse("2026-09-01T03:00:00Z"),
                    money("1000")));

    @Test
    void oldestFirstByBusinessDateThenByArrival() {
        assertThat(Allocator.oldestFirst(OPEN, money("1200")))
                .containsExactly(new Allocation(OLD, money("1000")), new Allocation(SAME_DAY_LATER, money("200")));
    }

    /** Wave 2 (CR-27A-1 item 2): a void's credit undoes its own receipt's charge before anything older. */
    @Test
    void aCreditSettlesTheChargeOfItsOwnDocumentFirstThenOldestFirst() {
        assertThat(Allocator.sameDocumentFirst(OPEN, NEW_RECEIPT, money("700")))
                .containsExactly(new Allocation(NEW, money("700")));
        assertThat(Allocator.sameDocumentFirst(OPEN, NEW_RECEIPT, money("900")))
                .containsExactly(new Allocation(NEW, money("700")), new Allocation(OLD, money("200")));
        // A refund's document matches no charge: oldest first, as a payment.
        assertThat(Allocator.sameDocumentFirst(OPEN, UUID.randomUUID(), money("1200")))
                .containsExactly(new Allocation(OLD, money("1000")), new Allocation(SAME_DAY_LATER, money("200")));
        assertThat(Allocator.sameDocumentFirst(OPEN, null, money("100")))
                .containsExactly(new Allocation(OLD, money("100")));
    }

    @Test
    void anOverpaymentSettlesEverythingAndLeavesTheRest() {
        assertThat(Allocator.oldestFirst(OPEN, money("5000")))
                .extracting(Allocation::amount)
                .containsExactly(money("1000"), money("300"), money("700"));
        assertThat(Allocator.oldestFirst(List.of(), money("5000"))).isEmpty();
    }

    @Test
    void specificAllocationsAreCheckedAgainstWhatIsOpenAndThePayment() {
        assertThat(Allocator.specific(OPEN, List.of(new Allocation(NEW, money("700"))), money("800")))
                .containsExactly(new Allocation(NEW, money("700")));
        assertThatThrownBy(() -> Allocator.specific(OPEN, List.of(new Allocation(NEW, money("701"))), money("800")))
                .hasMessageContaining("m7.payment.exceeds_open");
        assertThatThrownBy(() -> Allocator.specific(
                        OPEN,
                        List.of(new Allocation(NEW, money("500")), new Allocation(OLD, money("400"))),
                        money("800")))
                .hasMessageContaining("m7.payment.exceeds_amount");
        assertThatThrownBy(() -> Allocator.specific(
                        OPEN, List.of(new Allocation(NEW, money("1")), new Allocation(NEW, money("1"))), money("800")))
                .hasMessageContaining("m7.payment.charge_invalid");
        assertThatThrownBy(() ->
                        Allocator.specific(OPEN, List.of(new Allocation(UUID.randomUUID(), money("1"))), money("8")))
                .hasMessageContaining("m7.payment.charge_invalid");
        assertThatThrownBy(() -> Allocator.specific(OPEN, List.of(new Allocation(NEW, money("0"))), money("8")))
                .hasMessageContaining("m7.payment.amount_invalid");
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2);
    }
}
