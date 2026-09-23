package lk.coopfed.knoweb;

import lk.coopfed.knoweb.engine.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedEngineSmokeTest {

    @Test
    void backendConsumesSharedEngine() {

        Money result = Money.roundCash(
                Money.of("100.50"));

        assertEquals(
                Money.of("101.00"),
                result);
    }
}
