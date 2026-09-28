package lk.coopfed.knoweb.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.demo.DemoCast.Actor;
import lk.coopfed.knoweb.demo.DemoTradingHistory.Lane;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.RecordChequeOutcome;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt;
import lk.coopfed.knoweb.m4trading.query.InvoiceBalance;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentReceiptView;
import org.springframework.stereotype.Service;

/**
 * DEMO-02, M4-07: the buyers pay some of the history's invoices, so the invoices, the payments and
 * the exposure screens open with something in them. Each payment is recorded by the seller's
 * accounts through RecordPaymentReceipt, a week after the invoice (never after today), against
 * the lane's invoices oldest first: some in full by transfer or by a cheque that clears, one in
 * part in cash, and one by a cheque that bounced three days later (the receipt reversed and the
 * invoice open again).
 *
 * <p>Idempotent: a payment's reference is its key ("DEMO-PAY ..."); a second run finds it among
 * the seller's receipts and records nothing, and records a cheque's outcome only while it has none.
 */
@Service
class DemoPayments {

    enum Kind {
        FULL_TRANSFER,
        FULL_CHEQUE_CLEARED,
        HALF_CASH,
        CHEQUE_BOUNCED
    }

    /** One payment of the demo: which invoice of the lane (oldest first, from 0) and how. */
    record Planned(String lane, int invoice, Kind kind) {}

    static final List<Planned> PLAN = List.of(
            new Planned("FED-D101", 0, Kind.FULL_TRANSFER),
            new Planned("FED-D101", 1, Kind.FULL_CHEQUE_CLEARED),
            new Planned("FED-D101", 2, Kind.HALF_CASH),
            new Planned("FED-D101", 3, Kind.CHEQUE_BOUNCED),
            new Planned("FED-D102", 0, Kind.FULL_TRANSFER),
            new Planned("D101-M101", 0, Kind.FULL_TRANSFER),
            new Planned("D101-M101", 1, Kind.HALF_CASH),
            new Planned("D101-M102", 0, Kind.FULL_CHEQUE_CLEARED));

    static final String REFERENCE_PREFIX = "DEMO-PAY ";

    private static final LocalTime PAID = LocalTime.of(11, 30);
    private static final LocalTime OUTCOME = LocalTime.of(10, 0);

    private final Handles<RecordPaymentReceipt, UUID> recordPayment;
    private final Handles<RecordChequeOutcome, UUID> chequeOutcome;
    private final InvoiceQueries invoices;
    private final PaymentQueries payments;
    private final DemoCalendar calendar;
    private final Clock clock;

    DemoPayments(
            Handles<RecordPaymentReceipt, UUID> recordPayment,
            Handles<RecordChequeOutcome, UUID> chequeOutcome,
            InvoiceQueries invoices,
            PaymentQueries payments,
            DemoCalendar calendar,
            Clock clock) {
        this.recordPayment = recordPayment;
        this.chequeOutcome = chequeOutcome;
        this.invoices = invoices;
        this.payments = payments;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** One act of the plan on its day: a payment recorded, or its cheque's outcome (outcome non-null). */
    private record Step(LocalDate day, LocalTime at, Planned plan, Lane lane, InvoiceView invoice, String outcome) {}

    /**
     * Records whatever of the plan is missing; {@code count} is told each command issued. The acts
     * of every lane run together in date order, so each series (the Federation's receipts serve
     * two lanes) numbers its receipts and reversals in the order of their dates.
     */
    void load(Consumer<String> count) {
        Map<String, Lane> lanes = DemoTradingHistory.LANES.stream().collect(Collectors.toMap(Lane::code, lane -> lane));
        LocalDate today = calendar.today();
        List<Step> steps = new ArrayList<>();
        for (Planned plan : PLAN) {
            Lane lane = lanes.get(plan.lane());
            List<InvoiceView> theirs =
                    invoices.listInvoices(OrderQueries.Role.SELLER, scopeOf(lane.accounts())).stream()
                            .filter(invoice -> lane.buyer().entityId().equals(invoice.buyerEntityId()))
                            .sorted(Comparator.comparing(InvoiceView::taxPointDate)
                                    .thenComparing(InvoiceView::docNumberDisplay))
                            .toList();
            if (plan.invoice() >= theirs.size()) {
                continue; // the history has fewer invoices than planned
            }
            InvoiceView invoice = theirs.get(plan.invoice());
            LocalDate paid = min(invoice.taxPointDate().plusDays(7), today);
            steps.add(new Step(paid, PAID, plan, lane, invoice, null));
            String outcome =
                    switch (plan.kind()) {
                        case FULL_CHEQUE_CLEARED -> RecordChequeOutcome.CLEARED;
                        case CHEQUE_BOUNCED -> RecordChequeOutcome.BOUNCED;
                        default -> null;
                    };
            if (outcome != null) {
                steps.add(new Step(min(paid.plusDays(3), today), OUTCOME, plan, lane, invoice, outcome));
            }
        }
        // Stable: on one day the outcomes (10:00) come before the payments (11:30), as the clock has it.
        steps.sort(Comparator.comparing(Step::day).thenComparing(Step::at));
        for (Step step : steps) {
            if (step.outcome() == null) {
                record(step, count);
            } else {
                outcome(step, count);
            }
        }
    }

    private void record(Step step, Consumer<String> count) {
        ScopeContext accounts = scopeOf(step.lane().accounts());
        if (receipt(step, accounts).isPresent()) {
            return;
        }
        BigDecimal due = invoices.balance(step.invoice().invoiceId(), accounts)
                .map(InvoiceBalance::amountDue)
                .orElse(BigDecimal.ZERO);
        BigDecimal amount =
                step.plan().kind() == Kind.HALF_CASH ? due.divide(BigDecimal.TWO, 2, RoundingMode.DOWN) : due;
        if (amount.signum() <= 0) {
            return;
        }
        calendar.at(
                step.day(),
                step.at(),
                () -> recordPayment.handle(
                        new RecordPaymentReceipt(
                                step.lane().buyer().entityId(),
                                method(step.plan().kind()),
                                amount,
                                reference(step.plan()),
                                step.day(),
                                cheque(step.plan(), step.day()),
                                List.of(new RecordPaymentReceipt.Settlement(
                                        step.invoice().invoiceId(), amount))),
                        scopeOf(step.lane().accounts())));
        count.accept("RecordPaymentReceipt");
    }

    private void outcome(Step step, Consumer<String> count) {
        ScopeContext accounts = scopeOf(step.lane().accounts());
        Optional<PaymentReceiptView> receipt = receipt(step, accounts);
        if (receipt.isEmpty()
                || receipt.get().cheque() == null
                || receipt.get().cheque().outcome() != null) {
            return;
        }
        UUID receiptId = receipt.get().receiptId();
        String reason =
                RecordChequeOutcome.BOUNCED.equals(step.outcome()) ? "Returned by the bank: refer to drawer" : null;
        calendar.run(
                step.day(),
                step.at(),
                () -> chequeOutcome.handle(
                        new RecordChequeOutcome(receiptId, step.outcome(), reason),
                        scopeOf(step.lane().accounts())));
        count.accept("RecordChequeOutcome");
    }

    /** The payment of the plan, found by its reference among the seller's receipts. */
    private Optional<PaymentReceiptView> receipt(Step step, ScopeContext accounts) {
        String reference = reference(step.plan());
        return payments.listReceipts(OrderQueries.Role.SELLER, accounts).stream()
                .filter(receipt -> reference.equals(receipt.reference()))
                .findFirst();
    }

    static String reference(Planned plan) {
        return REFERENCE_PREFIX + plan.lane() + " " + (plan.invoice() + 1);
    }

    private static String method(Kind kind) {
        return switch (kind) {
            case FULL_TRANSFER -> "TRANSFER";
            case HALF_CASH -> "CASH";
            case FULL_CHEQUE_CLEARED, CHEQUE_BOUNCED -> RecordPaymentReceipt.CHEQUE;
        };
    }

    private static RecordPaymentReceipt.Cheque cheque(Planned plan, LocalDate paid) {
        if (plan.kind() != Kind.FULL_CHEQUE_CLEARED && plan.kind() != Kind.CHEQUE_BOUNCED) {
            return null;
        }
        String bank = plan.kind() == Kind.CHEQUE_BOUNCED ? "People's Bank" : "Bank of Ceylon";
        return new RecordPaymentReceipt.Cheque(bank, String.valueOf(400100 + plan.invoice()), paid);
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? b : a;
    }

    private ScopeContext scopeOf(Actor actor) {
        Scope scope = new Scope(actor.entityId(), actor.locationId());
        return new ScopeContext(
                actor.userId(),
                null,
                actor.entityId(),
                List.of(scope),
                scope,
                PolicyClass.OWN,
                Set.of(),
                clock.instant(),
                Locale.ENGLISH,
                null);
    }
}
