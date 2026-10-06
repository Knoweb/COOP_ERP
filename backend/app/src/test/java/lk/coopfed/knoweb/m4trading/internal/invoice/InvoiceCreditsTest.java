package lk.coopfed.knoweb.m4trading.internal.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import org.junit.jupiter.api.Test;

/**
 * The remainder rule of M4MONEY-05: slices of an invoice line, each rounded to the cent on its own,
 * never credit more than the line billed, because the last slice credits exactly what is left.
 */
class InvoiceCreditsTest {

    private static final UUID INVOICE = UUID.randomUUID();
    private static final UUID CREDIT_NOTE = UUID.randomUUID();

    /** 2 x 0.3350 = 0.67, with the VAT the invoice computes on that net. */
    private static DocumentLineRecord billed(String ratePercent, String tax) {
        return TradingDocuments.line(
                UUID.randomUUID(),
                INVOICE,
                1,
                UUID.randomUUID(),
                null,
                "EA",
                new BigDecimal("2.000"),
                new BigDecimal("0.3350"),
                new BigDecimal(ratePercent),
                new BigDecimal(tax),
                new BigDecimal("0.67"),
                null,
                UUID.randomUUID());
    }

    @Test
    void twoHalfCreditsOfALineCreditWhatItBilledAndNoMore() {
        DocumentLineRecord line = billed("0", "0.00");

        InvoiceCredits.Credited left = InvoiceCredits.remaining(line, null);
        DocumentLineRecord first = InvoiceCredits.priced(CREDIT_NOTE, 1, line, left, BigDecimal.ONE);
        // 0.335 rounds half up to 0.34 on its own.
        assertThat(first.lineTotal()).isEqualByComparingTo("0.34");

        InvoiceCredits.Credited afterFirst = InvoiceCredits.remaining(
                line, new InvoiceCredits.Credited(first.qty(), first.lineTotal(), first.taxAmount()));
        DocumentLineRecord second = InvoiceCredits.priced(CREDIT_NOTE, 1, line, afterFirst, BigDecimal.ONE);

        // The last slice takes the remainder: 0.33, so the two credit 0.67, not 0.68.
        assertThat(second.lineTotal()).isEqualByComparingTo("0.33");
        assertThat(first.lineTotal().add(second.lineTotal())).isEqualByComparingTo("0.67");
        assertThat(second.referenceLineId()).isEqualTo(line.id());
    }

    @Test
    void theTaxOfTheLastSliceIsTheRemainderToo() {
        // 0.67 at 18 % is 0.12 on the invoice; one unit's 0.34 at 18 % rounds to 0.06 each time.
        DocumentLineRecord line = billed("18", "0.12");

        DocumentLineRecord first =
                InvoiceCredits.priced(CREDIT_NOTE, 1, line, InvoiceCredits.remaining(line, null), BigDecimal.ONE);
        DocumentLineRecord second = InvoiceCredits.priced(
                CREDIT_NOTE,
                1,
                line,
                InvoiceCredits.remaining(
                        line, new InvoiceCredits.Credited(first.qty(), first.lineTotal(), first.taxAmount())),
                BigDecimal.ONE);

        assertThat(first.taxAmount()).isEqualByComparingTo("0.06");
        assertThat(first.taxAmount().add(second.taxAmount())).isEqualByComparingTo("0.12");
    }

    @Test
    void aSliceThatIsNotTheLastIsPricedAndCappedAtWhatIsLeft() {
        DocumentLineRecord line = billed("0", "0.00");
        // 1.999 x 0.3350 = 0.669665, 0.67 rounded: no more than the 0.67 left.
        DocumentLineRecord slice = InvoiceCredits.priced(
                CREDIT_NOTE, 1, line, InvoiceCredits.remaining(line, null), new BigDecimal("1.999"));
        assertThat(slice.lineTotal()).isEqualByComparingTo("0.67");

        // Whatever is left of the money goes with the last 0.001.
        DocumentLineRecord last = InvoiceCredits.priced(
                CREDIT_NOTE,
                1,
                line,
                InvoiceCredits.remaining(
                        line, new InvoiceCredits.Credited(slice.qty(), slice.lineTotal(), slice.taxAmount())),
                new BigDecimal("0.001"));
        assertThat(last.lineTotal()).isEqualByComparingTo("0.00");
    }
}
