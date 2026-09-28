import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useT } from "../../shell/i18n/useT";
import type { Exposure } from "./tradingApi";
import { exposureAfter, percentOfLimit } from "./tradingView";

/**
 * The exposure of one relationship (24A section 8, "Statement / Exposure": the components table):
 * open invoices plus accepted orders not yet invoiced, less what was paid on account, beside the
 * credit limit the seller set (M1). With `orderValue`, the order desk also shows what accepting
 * the order would take it to. It warns and never blocks: the server accepts an order over the
 * limit (ADR-12), so nothing here disables a button.
 */
export function ExposurePanel({ exposure, orderValue }: { exposure: Exposure; orderValue?: number }) {
  const t = useT();
  const percent = percentOfLimit(exposure.amount, exposure.creditLimit);
  const after = orderValue === undefined ? null : exposureAfter(exposure, orderValue);

  return (
    <section className="trading-section" aria-label={t("trading.exposure.title").text}>
      <h2>{t("trading.exposure.title").text}</h2>
      <dl className="document-header__facts">
        <div className="document-header__fact">
          <dt>{t("trading.exposure.limit").text}</dt>
          <dd>
            {exposure.creditLimit === undefined || exposure.creditLimit === null ? (
              t("trading.exposure.no_limit").text
            ) : (
              <MoneyDisplay amount={exposure.creditLimit} />
            )}
          </dd>
        </div>
        <div className="document-header__fact">
          <dt>{t("trading.exposure.open_invoices").text}</dt>
          <dd>
            <MoneyDisplay amount={exposure.openInvoices} />
          </dd>
        </div>
        <div className="document-header__fact">
          <dt>{t("trading.exposure.accepted_not_invoiced").text}</dt>
          <dd>
            <MoneyDisplay amount={exposure.acceptedNotInvoiced} />
          </dd>
        </div>
        <div className="document-header__fact">
          <dt>{t("trading.exposure.on_account").text}</dt>
          <dd>
            <MoneyDisplay amount={exposure.unappliedReceipts} />
          </dd>
        </div>
        <div className="document-header__fact">
          <dt>{t("trading.exposure.amount").text}</dt>
          <dd>
            <MoneyDisplay amount={exposure.amount} size="total" />
            {percent !== null && <span> {t("trading.exposure.percent", undefined, { percent }).text}</span>}
          </dd>
        </div>
      </dl>
      {exposure.warnThresholdPercent !== undefined && exposure.warnThresholdPercent !== null && (
        <p role="status" className="trading-warning">
          {t("trading.exposure.warning", undefined, { percent: exposure.warnThresholdPercent }).text}
        </p>
      )}
      {after && (
        <p role="status">
          {t("trading.exposure.after_accept").text} <MoneyDisplay amount={after.amount} />
          {after.percent !== null && <span> {t("trading.exposure.percent", undefined, { percent: after.percent }).text}</span>}
          {after.overLimit && <strong className="trading-warning"> {t("trading.exposure.over_limit").text}</strong>}
        </p>
      )}
    </section>
  );
}
