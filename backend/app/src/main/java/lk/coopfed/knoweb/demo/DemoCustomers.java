package lk.coopfed.knoweb.demo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lk.coopfed.knoweb.demo.DemoCast.Actor;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.api.ChangeAccountStatus;
import lk.coopfed.knoweb.m7customers.api.FulfilDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.PostAccountTender;
import lk.coopfed.knoweb.m7customers.api.RecordCustomerPayment;
import lk.coopfed.knoweb.m7customers.api.RecordDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.m7customers.query.CustomerQueries;
import lk.coopfed.knoweb.m7customers.query.CustomerSummary;
import lk.coopfed.knoweb.m7customers.query.PrivacyQueries;
import org.springframework.stereotype.Service;

/**
 * M7 (back office): the members of Kuliyapitiya MPCS (M101) and its credit book. The society office
 * registers three dozen members with Sinhala, Tamil and English names, half of them with a credit
 * account; their account sales over the eight weeks of the history are charged, and they repay at
 * the office every few weeks. One account (the first) stands near its limit; one (the second) was
 * suspended yesterday; one member with no account asked to be forgotten, and the society's
 * responsible officer (the manager, {@link DemoCast#M101_MANAGER}) erased their identity.
 *
 * <p>Who does what: the office clerk ({@link #M101_OFFICE}, demo-users.demo.sql) registers, opens
 * the accounts and records the repayments through the ordinary handlers. The charges are the till's
 * facts: they go through {@code PostAccountTender}, the handler the receipt tender consumer feeds
 * from {@code receipt.issued.v1} (27A section 6.2), with the system scope a till's upload runs in
 * (no user) at the town shop. The till is later (CR-30-1), so these charges have no M6 receipt
 * behind them: their numbers say so ({@code DEMO-KHATA-...}).
 *
 * <p>Idempotent: a member is found by phone, an account on the card, a charge by its receipt id
 * (fixed per member and day) and a repayment by the business date of its PAYMENT on the statement;
 * a second run issues no command. The phone numbers and NIC numbers are plainly made up
 * ({@code 070 000 01nn}, {@code 1900000000nn}).
 */
@Service
class DemoCustomers {

    /** The society office clerk of Kuliyapitiya MPCS (seed/m1security/demo-users.demo.sql, realm-dev.json). */
    static final Actor M101_OFFICE =
            new Actor("m101-office", UUID.fromString("0190f0de-0000-7000-8000-000000000234"), DemoCast.M101, null);

    /** A member of the society: names in the three languages and the language of their statements. */
    record Member(String en, String si, String ta, String language) {}

    static final List<Member> MEMBERS = List.of(
            new Member("K. Perera", "කේ. පෙරේරා", "கே. பெரேரா", "si"),
            new Member("W. M. Senanayake", "ඩබ්. එම්. සේනානායක", "டபிள்யூ. எம். சேனநாயக்க", "si"),
            new Member("Sita Kumari", "සීතා කුමාරි", "சீதா குமாரி", "si"),
            new Member("R. Wijesinghe", "ආර්. විජේසිංහ", "ஆர். விஜேசிங்க", "si"),
            new Member("Anoma Herath", "අනෝමා හේරත්", "அனோமா ஹேரத்", "si"),
            new Member("S. Sivakumar", "එස්. සිවකුමාර්", "எஸ். சிவகுமார்", "ta"),
            new Member("Mohamed Fazil", "මොහොමඩ් ෆාසිල්", "மொஹமட் பாசில்", "en"),
            new Member("Chandrika Ranasinghe", "චන්ද්‍රිකා රණසිංහ", "சந்திரிகா ரணசிங்க", "si"),
            new Member("P. Jayawardena", "පී. ජයවර්ධන", "பி. ஜயவர்த்தன", "si"),
            new Member("Lakshmi Nadarajah", "ලක්ෂ්මි නඩරාජා", "லட்சுமி நடராஜா", "ta"),
            new Member("Nimal Rathnayake", "නිමල් රත්නායක", "நிமல் ரத்நாயக்க", "si"),
            new Member("Fathima Rinoza", "ෆාතිමා රිනෝසා", "பாத்திமா ரினோசா", "en"),
            new Member("Sunil Bandara", "සුනිල් බණ්ඩාර", "சுனில் பண்டார", "si"),
            new Member("K. Thevarajah", "කේ. තේවරාජා", "கே. தேவராஜா", "ta"),
            new Member("Dilrukshi Fernando", "දිල්රුක්ෂි ප්‍රනාන්දු", "தில்ருக்ஷி பெர்னாண்டோ", "si"),
            new Member("A. M. Karunaratne", "ඒ. එම්. කරුණාරත්න", "ஏ. எம். கருணாரத்ன", "si"),
            new Member("Mala Weerasinghe", "මාලා වීරසිංහ", "மாலா வீரசிங்க", "si"),
            new Member("Ramesh Kanagaratnam", "රමේෂ් කනගරත්නම්", "ரமேஷ் கனகரத்தினம்", "ta"),
            new Member("Kamala Dissanayake", "කමලා දිසානායක", "கமலா திசாநாயக்க", "si"),
            new Member("H. Gunasekara", "එච්. ගුණසේකර", "எச். குணசேகர", "si"),
            new Member("Ayesha Siddique", "අයේෂා සිද්දික්", "ஆயிஷா சித்திக்", "en"),
            new Member("T. Rajapaksha", "ටී. රාජපක්ෂ", "டி. ராஜபக்ஷ", "si"),
            new Member("Priyanka Samarakoon", "ප්‍රියංකා සමරකෝන්", "பிரியங்கா சமரகோன்", "si"),
            new Member("V. Balasubramaniam", "වී. බාලසුබ්‍රමනියම්", "வி. பாலசுப்பிரமணியம்", "ta"),
            new Member("Gamini Ekanayake", "ගාමිණී ඒකනායක", "காமினி ஏக்கநாயக்க", "si"),
            new Member("Shirani Abeysekera", "ශිරානි අබේසේකර", "ஷிரானி அபேசேகர", "si"),
            new Member("M. I. Nazeer", "එම්. අයි. නසීර්", "எம். ஐ. நசீர்", "en"),
            new Member("Upali Wickramasinghe", "උපාලි වික්‍රමසිංහ", "உபாலி விக்கிரமசிங்க", "si"),
            new Member("Kanthi Premaratne", "කාන්ති ප්‍රේමරත්න", "காந்தி பிரேமரத்ன", "si"),
            new Member("S. Yogeswaran", "එස්. යෝගේශ්වරන්", "எஸ். யோகேஸ்வரன்", "ta"),
            new Member("Jayantha Kulatunga", "ජයන්ත කුලතුංග", "ஜயந்த குலதுங்க", "si"),
            new Member("Renuka Amarasekara", "රේණුකා අමරසේකර", "ரேணுகா அமரசேகர", "si"),
            new Member("D. Mahendran", "ඩී. මහේන්ද්‍රන්", "டி. மகேந்திரன்", "ta"),
            new Member("Sarath Kumara", "සරත් කුමාර", "சரத் குமார", "si"),
            new Member("Nadeesha Liyanage", "නදීෂා ලියනගේ", "நதீஷா லியனகே", "si"),
            new Member("Rizwan Hameed", "රිස්වාන් හමීඩ්", "ரிஸ்வான் ஹமீட்", "en"));

    /** The limits of the accounts in turn; the first account's is the one it stands near. */
    static final List<BigDecimal> LIMITS = List.of(
            new BigDecimal("15000.00"),
            new BigDecimal("10000.00"),
            new BigDecimal("20000.00"),
            new BigDecimal("25000.00"));

    /** The first account's weekly charges and its one repayment: 16,200 less 2,000, near its 15,000. */
    static final List<BigDecimal> NEAR_LIMIT_CHARGES = List.of(
            new BigDecimal("1500.00"),
            new BigDecimal("2200.00"),
            new BigDecimal("1800.00"),
            new BigDecimal("2500.00"),
            new BigDecimal("1700.00"),
            new BigDecimal("2100.00"),
            new BigDecimal("2400.00"),
            new BigDecimal("2000.00"));

    static final BigDecimal NEAR_LIMIT_PAYMENT = new BigDecimal("2000.00");

    /** The members were registered and their accounts opened this many days before the load. */
    static final int REGISTERED_DAYS_AGO = DemoCalendar.HISTORY_DAYS + 1;

    static final String NUMBER_PREFIX = "DEMO-KHATA-";

    /** The second account (member 3, Sita Kumari) is suspended the day before the load. */
    static final int SUSPENDED_ACCOUNT = 1;

    static final String SUSPENSION_REASON = "Repayments overdue: suspended until the member calls at the office";

    /** The last member (Rizwan Hameed) has no account, owes nothing, and asked to be forgotten. */
    static final int ERASED_MEMBER = 35;

    private static final LocalTime SUSPENDED_AT = LocalTime.of(17, 0);
    private static final LocalTime ERASURE_ASKED_AT = LocalTime.of(11, 0);
    private static final LocalTime ERASURE_DONE_AT = LocalTime.of(14, 30);

    private static final LocalTime REGISTERED = LocalTime.of(9, 30);
    private static final LocalTime REPAID = LocalTime.of(15, 0);
    private static final List<String> METHODS = List.of("CASH", "TRANSFER", "CASH", "DEPOSIT");

    private final Handles<RegisterCustomer, UUID> register;
    private final Handles<OpenAccount, UUID> openAccount;
    private final Handles<PostAccountTender, UUID> postTender;
    private final Handles<RecordCustomerPayment, UUID> recordPayment;
    private final Handles<ChangeAccountStatus, UUID> changeStatus;
    private final Handles<RecordDataSubjectRequest, UUID> recordRequest;
    private final Handles<FulfilDataSubjectRequest, UUID> fulfilRequest;
    private final CustomerQueries customers;
    private final AccountQueries accounts;
    private final PrivacyQueries privacy;
    private final DemoCalendar calendar;
    private final Clock clock;

    @SuppressWarnings("java:S107") // the handlers and queries of the society's credit book
    DemoCustomers(
            Handles<RegisterCustomer, UUID> register,
            Handles<OpenAccount, UUID> openAccount,
            Handles<PostAccountTender, UUID> postTender,
            Handles<RecordCustomerPayment, UUID> recordPayment,
            Handles<ChangeAccountStatus, UUID> changeStatus,
            Handles<RecordDataSubjectRequest, UUID> recordRequest,
            Handles<FulfilDataSubjectRequest, UUID> fulfilRequest,
            CustomerQueries customers,
            AccountQueries accounts,
            PrivacyQueries privacy,
            DemoCalendar calendar,
            Clock clock) {
        this.register = register;
        this.openAccount = openAccount;
        this.postTender = postTender;
        this.recordPayment = recordPayment;
        this.changeStatus = changeStatus;
        this.recordRequest = recordRequest;
        this.fulfilRequest = fulfilRequest;
        this.customers = customers;
        this.accounts = accounts;
        this.privacy = privacy;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** One act of an account's history: a charge (amount above zero) or a repayment (below zero). */
    record Step(LocalDate day, LocalTime at, int member, BigDecimal amount, String method) {}

    /** Loads whatever is missing; {@code count} is told each command issued. */
    void load(Consumer<String> count) {
        LocalDate today = calendar.today();
        ScopeContext office = scopeOf(M101_OFFICE);
        // The member whose identity was erased has no phone to be found by any more: the fulfilled
        // erasure names them.
        UUID erased =
                erasure(office).map(PrivacyQueries.RequestView::customerId).orElse(null);
        List<UUID> accountIds = new ArrayList<>();
        for (int i = 0; i < MEMBERS.size(); i++) {
            int index = i;
            if (index == ERASED_MEMBER && erased != null) {
                continue;
            }
            UUID customerId = calendar.at(
                    today.minusDays(REGISTERED_DAYS_AGO),
                    REGISTERED.plusMinutes(i),
                    () -> member(index, office, count));
            if (hasAccount(i)) {
                accountIds.add(calendar.at(
                        today.minusDays(REGISTERED_DAYS_AGO),
                        REGISTERED.plusMinutes(i),
                        () -> account(index, customerId, office, count)));
            }
        }
        // The history of every account in date order, so the society's CPR numbers run with the dates.
        List<Step> steps = new ArrayList<>();
        for (int a = 0; a < accountIds.size(); a++) {
            steps.addAll(history(a, today));
        }
        steps.sort(Comparator.comparing(Step::day).thenComparing(Step::at));
        Map<Integer, Set<UUID>> charged = new java.util.HashMap<>();
        Map<Integer, Set<LocalDate>> repaid = new java.util.HashMap<>();
        for (Step step : steps) {
            UUID accountId = accountIds.get(step.member());
            if (!charged.containsKey(step.member())) {
                Set<UUID> documents = new HashSet<>();
                Set<LocalDate> payments = new HashSet<>();
                accounts.statement(accountId, today.minusDays(REGISTERED_DAYS_AGO), today, office)
                        .orElseThrow()
                        .lines()
                        .forEach(line -> {
                            documents.add(line.documentId());
                            if ("PAYMENT".equals(line.kind())) {
                                payments.add(line.businessDate());
                            }
                        });
                charged.put(step.member(), documents);
                repaid.put(step.member(), payments);
            }
            if (step.amount().signum() > 0) {
                UUID receipt = receiptId(step);
                if (!charged.get(step.member()).contains(receipt)) {
                    calendar.run(
                            step.day(),
                            step.at(),
                            () -> postTender.handle(
                                    new PostAccountTender(
                                            PostAccountTender.CHARGE,
                                            accountId,
                                            step.amount(),
                                            receipt,
                                            NUMBER_PREFIX + step.day() + "-" + (step.member() + 1),
                                            1,
                                            DemoCast.M101_TOWN_SHOP,
                                            step.day(),
                                            null,
                                            false),
                                    tillScope()));
                    count.accept("PostAccountTender");
                }
            } else if (!repaid.get(step.member()).contains(step.day())) {
                calendar.at(
                        step.day(),
                        step.at(),
                        () -> recordPayment.handle(
                                new RecordCustomerPayment(
                                        accountId,
                                        step.method(),
                                        step.amount().negate(),
                                        "DEMO-KHATA repayment",
                                        RecordCustomerPayment.OLDEST_FIRST,
                                        List.of()),
                                scopeOf(M101_OFFICE)));
                count.accept("RecordCustomerPayment");
            }
        }

        // One account suspended after its last charge: the office stops the tills taking more.
        UUID suspended = accountIds.get(SUSPENDED_ACCOUNT);
        if ("OPEN".equals(accounts.account(suspended, office).orElseThrow().status())) {
            calendar.run(
                    today.minusDays(1),
                    SUSPENDED_AT,
                    () -> changeStatus.handle(
                            new ChangeAccountStatus(suspended, ChangeAccountStatus.SUSPEND, SUSPENSION_REASON),
                            scopeOf(M101_OFFICE)));
            count.accept("ChangeAccountStatus");
        }

        // One member with nothing owed asked to be forgotten: the office records the request, the
        // society's responsible officer (the manager) fulfils it and the identity is anonymised.
        if (erased == null) {
            UUID member = customers.search(null, phone(ERASED_MEMBER), 1, office).stream()
                    .findFirst()
                    .orElseThrow()
                    .customerId();
            UUID requestId = calendar.at(
                    today.minusDays(3),
                    ERASURE_ASKED_AT,
                    () -> recordRequest.handle(
                            new RecordDataSubjectRequest(
                                    member, RecordDataSubjectRequest.ERASURE, "Asked at the office to be forgotten"),
                            scopeOf(M101_OFFICE)));
            count.accept("RecordDataSubjectRequest");
            calendar.run(
                    today.minusDays(2),
                    ERASURE_DONE_AT,
                    () -> fulfilRequest.handle(
                            new FulfilDataSubjectRequest(requestId, "No account and nothing owed: identity erased"),
                            scopeOf(DemoCast.M101_MANAGER)));
            count.accept("FulfilDataSubjectRequest");
        }
    }

    /** The fulfilled erasure of the demo, if it was made. */
    private Optional<PrivacyQueries.RequestView> erasure(ScopeContext office) {
        return privacy.requests("FULFILLED", office).stream()
                .filter(request -> RecordDataSubjectRequest.ERASURE.equals(request.kind()))
                .findFirst();
    }

    /** Every other member has an account. */
    static boolean hasAccount(int member) {
        return member % 2 == 0;
    }

    /** The plainly made-up phone number of a member: 070 000 01nn. */
    static String phone(int member) {
        return String.format("0700000%03d", 100 + member);
    }

    static String nic(int member) {
        return String.format("1900000000%02d", member);
    }

    /**
     * The history of the account-th account (member 2 × account): a charge each week on its own day
     * of the week, from eight weeks ago to yesterday, and a repayment of most of three weeks'
     * charges every third week. The first account charges more and repays once: it ends near its
     * limit.
     */
    static List<Step> history(int account, LocalDate today) {
        List<Step> steps = new ArrayList<>();
        BigDecimal sinceRepaid = BigDecimal.ZERO;
        for (int week = 0; week < 8; week++) {
            int daysAgo = DemoCalendar.HISTORY_DAYS - week * 7 - account % 7;
            if (daysAgo < 1) {
                break;
            }
            LocalDate day = today.minusDays(daysAgo);
            BigDecimal amount = account == 0
                    ? NEAR_LIMIT_CHARGES.get(week)
                    : BigDecimal.valueOf(500 + ((account * 37L + week * 113L) % 20) * 100)
                            .setScale(2);
            steps.add(new Step(day, LocalTime.of(10, 0).plusMinutes(account), account, amount, null));
            sinceRepaid = sinceRepaid.add(amount);
            boolean repays = account == 0 ? week == 3 : week % 3 == 2;
            if (repays && daysAgo > 1) {
                BigDecimal paid = account == 0
                        ? NEAR_LIMIT_PAYMENT
                        : sinceRepaid
                                .multiply(new BigDecimal("0.8"))
                                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.DOWN)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(2);
                steps.add(new Step(
                        day.plusDays(1),
                        REPAID.plusMinutes(account),
                        account,
                        paid.negate(),
                        METHODS.get((account + week) % METHODS.size())));
                sinceRepaid = BigDecimal.ZERO;
            }
        }
        return steps;
    }

    private UUID member(int index, ScopeContext office, Consumer<String> count) {
        Optional<CustomerSummary> found =
                customers.search(null, phone(index), 1, office).stream().findFirst();
        if (found.isPresent()) {
            return found.get().customerId();
        }
        Member member = MEMBERS.get(index);
        UUID id = register.handle(
                new RegisterCustomer(
                        member.en(),
                        member.si(),
                        member.ta(),
                        member.language(),
                        phone(index),
                        hasAccount(index) || index % 3 == 0
                                ? List.of("CREDIT_ACCOUNT", "STATEMENTS_NOTIFICATIONS")
                                : List.of("CREDIT_ACCOUNT"),
                        "PAPER",
                        Map.of("village", index % 2 == 0 ? "Kuliyapitiya" : "Hettipola"),
                        index % 5 == 0 ? List.of("regular") : List.of(),
                        false),
                office);
        count.accept("RegisterCustomer");
        return id;
    }

    private UUID account(int index, UUID customerId, ScopeContext office, Consumer<String> count) {
        var card = customers.card(customerId, office).orElseThrow();
        if (card.account() != null) {
            return card.account().accountId();
        }
        UUID id = openAccount.handle(
                new OpenAccount(customerId, LIMITS.get((index / 2) % LIMITS.size()), 30, null, nic(index)), office);
        count.accept("OpenAccount");
        return id;
    }

    /** The receipt a charge stands for: fixed per account and day, so a second run finds it. */
    static UUID receiptId(Step step) {
        return UUID.nameUUIDFromBytes(
                ("demo-khata:" + step.member() + ":" + step.day()).getBytes(StandardCharsets.UTF_8));
    }

    /** What a till's upload runs as (the consumer's scope): the society at its shop, no user. */
    private ScopeContext tillScope() {
        Scope scope = new Scope(DemoCast.M101, DemoCast.M101_TOWN_SHOP);
        return new ScopeContext(
                null,
                null,
                DemoCast.M101,
                List.of(scope),
                scope,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
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
