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

  const isOverLimit = exposure.creditLimit !== undefined && exposure.creditLimit !== null && exposure.amount > exposure.creditLimit;

  return (
    <section className="trading-section" aria-label={t("trading.exposure.title").text}>
      <h2>{t("trading.exposure.title").text}</h2>
      <dl className="exposure-kpi-grid">
        <div className="exposure-kpi-card exposure-kpi-card--primary">
          <div className="exposure-kpi-card__header">
            <dt className="exposure-kpi-title">{t("trading.exposure.limit").text}</dt>
            <div className="exposure-kpi-card__icon-badge" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <rect x="2" y="5" width="20" height="14" rx="2" />
                <line x1="2" y1="10" x2="22" y2="10" />
              </svg>
            </div>
          </div>
          <div className="exposure-kpi-card__body">
            <dd className="exposure-kpi-value">
              {exposure.creditLimit === undefined || exposure.creditLimit === null ? (
                t("trading.exposure.no_limit").text
              ) : (
                <MoneyDisplay amount={exposure.creditLimit} />
              )}
            </dd>
          </div>
        </div>

        <div className="exposure-kpi-card exposure-kpi-card--info">
          <div className="exposure-kpi-card__header">
            <dt className="exposure-kpi-title">{t("trading.exposure.open_invoices").text}</dt>
            <div className="exposure-kpi-card__icon-badge" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
                <line x1="16" y1="13" x2="8" y2="13" />
                <line x1="16" y1="17" x2="8" y2="17" />
              </svg>
            </div>
          </div>
          <div className="exposure-kpi-card__body">
            <dd className="exposure-kpi-value">
              <MoneyDisplay amount={exposure.openInvoices} />
            </dd>
          </div>
        </div>

        <div className="exposure-kpi-card exposure-kpi-card--warning">
          <div className="exposure-kpi-card__header">
            <dt className="exposure-kpi-title">{t("trading.exposure.accepted_not_invoiced").text}</dt>
            <div className="exposure-kpi-card__icon-badge" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z" />
                <path d="m3.3 7 8.7 5 8.7-5" />
                <path d="M12 22V12" />
              </svg>
            </div>
          </div>
          <div className="exposure-kpi-card__body">
            <dd className="exposure-kpi-value">
              <MoneyDisplay amount={exposure.acceptedNotInvoiced} />
            </dd>
          </div>
        </div>

        <div className="exposure-kpi-card exposure-kpi-card--success">
          <div className="exposure-kpi-card__header">
            <dt className="exposure-kpi-title">{t("trading.exposure.on_account").text}</dt>
            <div className="exposure-kpi-card__icon-badge" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <circle cx="12" cy="12" r="9" />
                <polyline points="8.5 12 11 14.5 15.5 9.5" />
              </svg>
            </div>
          </div>
          <div className="exposure-kpi-card__body">
            <dd className="exposure-kpi-value">
              <MoneyDisplay amount={exposure.unappliedReceipts} />
            </dd>
          </div>
        </div>

        {(exposure.unappliedCredits ?? 0) > 0 && (
          <div className="exposure-kpi-card exposure-kpi-card--info">
            <div className="exposure-kpi-card__header">
              <dt className="exposure-kpi-title">{t("trading.exposure.unapplied_credits").text}</dt>
              <div className="exposure-kpi-card__icon-badge" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                  <circle cx="12" cy="12" r="9" />
                  <line x1="12" y1="8" x2="12" y2="16" />
                  <line x1="8" y1="12" x2="16" y2="12" />
                </svg>
              </div>
            </div>
            <div className="exposure-kpi-card__body">
              <dd className="exposure-kpi-value">
                <MoneyDisplay amount={exposure.unappliedCredits ?? 0} />
              </dd>
            </div>
          </div>
        )}

        <div className={`exposure-kpi-card ${isOverLimit ? "exposure-kpi-card--alert" : "exposure-kpi-card--exposure"}`}>
          <div className="exposure-kpi-card__header">
            <dt className="exposure-kpi-title">{t("trading.exposure.amount").text}</dt>
            <div className="exposure-kpi-card__icon-badge" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
              </svg>
            </div>
          </div>
          <div className="exposure-kpi-card__body">
            <dd className="exposure-kpi-value">
              <MoneyDisplay amount={exposure.amount} />
            </dd>
            {percent !== null && <span className="exposure-kpi-subtitle">{t("trading.exposure.percent", undefined, { percent }).text}</span>}
          </div>
        </div>
      </dl>
      {exposure.warnThresholdPercent !== undefined && exposure.warnThresholdPercent !== null && (
        <p role="status" className="trading-warning">
          {t("trading.exposure.warning", undefined, { percent: exposure.warnThresholdPercent }).text}
        </p>
      )}
      {after && (
        <p role="status" className="trading-exposure-status">
          {t("trading.exposure.after_accept").text} <MoneyDisplay amount={after.amount} />
          {after.percent !== null && <span> {t("trading.exposure.percent", undefined, { percent: after.percent }).text}</span>}
          {after.overLimit && <strong className="trading-warning"> {t("trading.exposure.over_limit").text}</strong>}
        </p>
      )}
    </section>
  );
}
