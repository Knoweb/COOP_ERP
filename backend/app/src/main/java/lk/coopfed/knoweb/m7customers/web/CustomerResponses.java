package lk.coopfed.knoweb.m7customers.web;

import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.m7customers.query.AccountView;
import lk.coopfed.knoweb.m7customers.query.CustomerCard;
import lk.coopfed.knoweb.m7customers.query.CustomerSummary;
import lk.coopfed.knoweb.m7customers.query.PrivacyQueries;
import lk.coopfed.knoweb.m7customers.web.generated.Account;
import lk.coopfed.knoweb.m7customers.web.generated.AccountAgeing;
import lk.coopfed.knoweb.m7customers.web.generated.AccountHistoryEntry;
import lk.coopfed.knoweb.m7customers.web.generated.Adjustment;
import lk.coopfed.knoweb.m7customers.web.generated.CustomerCardConsentsInner;
import lk.coopfed.knoweb.m7customers.web.generated.CustomerCardPhonesInner;
import lk.coopfed.knoweb.m7customers.web.generated.Language;
import lk.coopfed.knoweb.m7customers.web.generated.PrivacyRequest;
import lk.coopfed.knoweb.m7customers.web.generated.Statement;
import lk.coopfed.knoweb.m7customers.web.generated.StatementLinesInner;

/** The small mappers of 17A section 4.4: from the module's views to the generated responses. */
final class CustomerResponses {

    private CustomerResponses() {}

    static lk.coopfed.knoweb.m7customers.web.generated.CustomerSummary summary(CustomerSummary view) {
        return new lk.coopfed.knoweb.m7customers.web.generated.CustomerSummary()
                .customerId(view.customerId())
                .displayName(view.displayName())
                .displayNameSi(view.displayNameSi())
                .displayNameTa(view.displayNameTa())
                .language(Language.fromValue(view.language()))
                .phone(view.phone())
                .status(lk.coopfed.knoweb.m7customers.web.generated.CustomerSummary.StatusEnum.fromValue(view.status()))
                .accountId(view.accountId())
                .accountNo(view.accountNo())
                .creditLimit(view.creditLimit())
                .balance(view.balance());
    }

    static lk.coopfed.knoweb.m7customers.web.generated.CustomerCard card(CustomerCard view) {
        return new lk.coopfed.knoweb.m7customers.web.generated.CustomerCard()
                .customerId(view.customerId())
                .displayName(view.displayName())
                .displayNameSi(view.displayNameSi())
                .displayNameTa(view.displayNameTa())
                .language(Language.fromValue(view.language()))
                .phone(view.phone())
                .nicLast4(view.nicLast4())
                .status(lk.coopfed.knoweb.m7customers.web.generated.CustomerCard.StatusEnum.fromValue(view.status()))
                .registeredAt(view.registeredAt())
                .registeredHere(view.registeredHere())
                .attributes(new java.util.TreeMap<>(view.attributes()))
                .tags(view.tags())
                .consents(view.consents().stream()
                        .map(consent -> new CustomerCardConsentsInner()
                                .purpose(CustomerCardConsentsInner.PurposeEnum.fromValue(consent.purpose()))
                                .grantedVia(CustomerCardConsentsInner.GrantedViaEnum.fromValue(consent.grantedVia()))
                                .grantedAt(consent.grantedAt())
                                .withdrawnAt(consent.withdrawnAt()))
                        .toList())
                .phones(view.phones().stream()
                        .map(phone -> new CustomerCardPhonesInner()
                                .phone(phone.phone())
                                .validFrom(phone.validFrom())
                                .validTo(phone.validTo())
                                .reason(phone.reason()))
                        .toList())
                .account(view.account() == null ? null : account(view.account()));
    }

    static Account account(AccountView view) {
        return new Account()
                .accountId(view.accountId())
                .accountNo(view.accountNo())
                .customerId(view.customerId())
                .creditLimit(view.creditLimit())
                .balance(view.balance())
                .available(view.available())
                .offlineCap(view.offlineCap())
                .termsDays(view.termsDays())
                .hardBlock(view.hardBlock())
                .status(Account.StatusEnum.fromValue(view.status()))
                .openedAt(view.openedAt())
                .oldestUnpaid(view.oldestUnpaid())
                .unallocated(view.unallocated())
                .ageing(new AccountAgeing()
                        .days0To30(view.ageing().days0To30())
                        .days31To60(view.ageing().days31To60())
                        .days61To90(view.ageing().days61To90())
                        .over90(view.ageing().over90()));
    }

    static Statement statement(AccountQueries.Statement view) {
        return new Statement()
                .accountId(view.accountId())
                .from(view.from())
                .to(view.to())
                .openingBalance(view.openingBalance())
                .closingBalance(view.closingBalance())
                .lines(view.lines().stream()
                        .map(line -> new StatementLinesInner()
                                .postingId(line.postingId())
                                .businessDate(line.businessDate())
                                .kind(StatementLinesInner.KindEnum.fromValue(line.kind()))
                                .amount(line.amount())
                                .settled(line.settled())
                                .runningBalance(line.runningBalance())
                                .documentId(line.documentId())
                                .documentNumber(line.documentNumber())
                                .limitBreached(line.limitBreached())
                                .offline(line.offline())
                                .reversed(line.reversed()))
                        .toList());
    }

    static AccountHistoryEntry history(AccountQueries.HistoryEntry entry) {
        return new AccountHistoryEntry()
                .historyId(entry.historyId())
                .action(AccountHistoryEntry.ActionEnum.fromValue(entry.action()))
                .before(entry.before())
                .after(entry.after())
                .reason(entry.reason())
                .changedBy(entry.changedBy())
                .changedAt(entry.changedAt());
    }

    static Adjustment adjustment(AccountQueries.Adjustment adjustment) {
        return new Adjustment()
                .adjustmentId(adjustment.adjustmentId())
                .amount(adjustment.amount())
                .reason(adjustment.reason())
                .status(Adjustment.StatusEnum.fromValue(adjustment.status()))
                .requestedBy(adjustment.requestedBy())
                .requestedAt(adjustment.requestedAt())
                .approvedBy(adjustment.approvedBy())
                .approvedAt(adjustment.approvedAt() == null ? null : adjustment.approvedAt());
    }

    static PrivacyRequest privacyRequest(PrivacyQueries.RequestView view) {
        return new PrivacyRequest()
                .requestId(view.requestId())
                .customerId(view.customerId())
                .customerName(view.customerName())
                .kind(PrivacyRequest.KindEnum.fromValue(view.kind()))
                .notes(view.notes())
                .receivedAt(view.receivedAt())
                .receivedBy(view.receivedBy())
                .status(PrivacyRequest.StatusEnum.fromValue(view.status()))
                .answeredAt(view.answeredAt() == null ? null : view.answeredAt())
                .answeredBy(view.answeredBy())
                .outcome(view.outcome())
                .exportSha256(view.exportSha256())
                .refusalGround(view.refusalGround());
    }
}
