package lk.coopfed.knoweb.m7customers.internal.queries;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m7customers.internal.ledger.Allocator.OpenCharge;
import lk.coopfed.knoweb.m7customers.query.AccountView;
import org.junit.jupiter.api.Test;

/** The ageing buckets (27A section 7): days since the charge's business date, on what is open of it. */
class AgeingTest {

    @Test
    void eachOpenAmountFallsInTheBucketOfItsAgeAndTheBucketsSumToTheOpenBalance() {
        LocalDate today = LocalDate.of(2026, 9, 29);
        List<OpenCharge> open = List.of(
                charge(today, "100"),
                charge(today.minusDays(30), "200"),
                charge(today.minusDays(31), "300"),
                charge(today.minusDays(60), "400"),
                charge(today.minusDays(61), "500"),
                charge(today.minusDays(90), "600"),
                charge(today.minusDays(91), "700"));

        AccountView.Ageing ageing = AccountQueriesImpl.ageing(open, today);

        assertThat(ageing.days0To30()).isEqualByComparingTo("300");
        assertThat(ageing.days31To60()).isEqualByComparingTo("700");
        assertThat(ageing.days61To90()).isEqualByComparingTo("1100");
        assertThat(ageing.over90()).isEqualByComparingTo("700");
    }

    private static OpenCharge charge(LocalDate day, String open) {
        return new OpenCharge(UUID.randomUUID(), UUID.randomUUID(), day, Instant.EPOCH, new BigDecimal(open));
    }
}
