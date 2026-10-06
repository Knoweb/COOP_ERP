package lk.coopfed.knoweb.m4trading.internal.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The order locks of a delivery note are taken in one order whatever order the note lists them in. */
class OrderLocksTest {

    private static final UUID A = UUID.fromString("0190f400-0000-7000-8000-0000000000a1");
    private static final UUID B = UUID.fromString("0190f400-0000-7000-8000-0000000000b2");
    private static final UUID C = UUID.fromString("0190f400-0000-7000-8000-0000000000c3");

    @Test
    void twoNotesOverTheSameOrdersLockThemInTheSameOrder() {
        assertThat(OrderLocks.sorted(List.of(C, A, B))).containsExactly(A, B, C);
        assertThat(OrderLocks.sorted(List.of(B, C, A))).containsExactly(A, B, C);
    }

    @Test
    void anOrderOnSeveralLinesIsLockedOnce() {
        assertThat(OrderLocks.sorted(List.of(B, A, B, A))).containsExactly(A, B);
    }
}
