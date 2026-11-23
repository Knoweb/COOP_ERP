import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { useScope } from "../../shell/scope/useScope";
import { DatePicker } from "../../shell/components/DatePicker";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { StateChip } from "../../shell/components/StateChip";
import { usePartyApi } from "./partyApi";
import { PartyName } from "./PartyName";
import { errorText } from "./SocietyRegisterPage";
import {
  LIMIT_REASONS,
  firstLimitDate,
  limitReady,
  limitRequest,
  pairHistory,
  relationshipChip
} from "./relationshipView";

/**
 * The agreement sheet of one trading relationship (21A section 8): the terms of this row, the
 * history of the pair (every effective-dated row, the latest first), and, for the seller's
 * authorised role, the credit limit changed from a date (AmendRelationshipTerms with the limit
 * only: bil.creditlimit.change and a fresh second factor, a reason for the audit record). The
 * amendment closes this row the day before and opens the next, which the page then shows; the
 * pair's open orders are judged by the row in force (M4), so they are not stranded.
 */
export function RelationshipPage() {
  const { relationshipId = "" } = useParams();
  const t = useT();
  const formatDate = useFormatDate();
  const api = usePartyApi();
  const scope = useScope();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const canAmend = useHasPermission("prt.relationship.amend");
  const key = useIdempotencyKey();
  const today = businessToday();
  const [limit, setLimit] = useState("");
  const [effectiveFrom, setEffectiveFrom] = useState("");
  const [asking, setAsking] = useState(false);

  const row = useQuery({
    queryKey: ["party", "relationship", relationshipId],
    queryFn: () => api.getRelationship(relationshipId)
  });
  const isSeller = row.data !== undefined && row.data.sellerEntityId === scope.entityId;
  const rows = useQuery({
    queryKey: ["party", "relationships", isSeller ? "SELLER" : "BUYER"],
    queryFn: () => api.listRelationships(isSeller ? "SELLER" : "BUYER"),
    enabled: row.data !== undefined
  });

  const amend = useMutation({
    mutationFn: (reason: { code: string; text: string | null }) =>
      api.amendRelationship(
        relationshipId,
        limitRequest(limit, effectiveFrom || firstLimitDate(row.data!, today), reason.code, reason.text),
        key.current()
      ),
    onSuccess: (next) => {
      key.next();
      setAsking(false);
      setLimit("");
      queryClient.invalidateQueries({ queryKey: ["party"] });
      queryClient.invalidateQueries({ queryKey: ["trading"] });
      navigate(`/party/relationships/${next.relationshipId}`);
    },
    onError: (error) => {
      setAsking(false);
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  if (row.isLoading) {
    return (
      <main className="shell-page">
        <div className="catalogue-state-card catalogue-state-card--loading">
          <div className="catalogue-state-spinner" aria-hidden="true" />
          <p>{t("party.list.loading").text}</p>
        </div>
      </main>
    );
  }
  if (row.isError || !row.data) {
    return (
      <main className="shell-page">
        <div>
          <Link className="back-pill-btn" to="/party/relationships">
            <svg
              viewBox="0 0 24 24"
              width="16"
              height="16"
              stroke="currentColor"
              strokeWidth="2"
              fill="none"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="m12 19-7-7 7-7" />
              <path d="M19 12H5" />
            </svg>
            {t("party.relationship.back").text}
          </Link>
        </div>
        <div className="catalogue-state-card catalogue-state-card--error" role="alert">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          <p>{errorText(row.error, t("party.error.generic").text)}</p>
        </div>
      </main>
    );
  }

  const r = row.data;
  const history = pairHistory(rows.data ?? [r], r);
  const latest = history[0]?.relationshipId === r.relationshipId;
  const inForce = r.status === "ACTIVE" && (!r.effectiveTo || r.effectiveTo >= today);
  // bil.creditlimit.change and the second factor are checked by the server (21A 6.1): no
  // operation carries that code, so the screen cannot ask the session for it.
  const mayChange = isSeller && canAmend && inForce && latest;
  const firstDay = firstLimitDate(r, today);
  const from = effectiveFrom || firstDay;

  return (
    <main className="shell-page relationship-page">
      <div>
        <Link className="back-pill-btn" to="/party/relationships">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            stroke="currentColor"
            strokeWidth="2"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("party.relationship.back").text}
        </Link>
      </div>
      {/* Hero Agreement Card */}
      <section className="relationship-hero-card">
        {/* Top Header Row */}
        <div className="relationship-hero-card__header">
          <div className="relationship-hero-card__title-group">
            <div className="relationship-hero-card__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="22" height="22" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
                <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
                <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
                <path d="M16 3.13a4 4 0 0 1 0 7.75" />
              </svg>
            </div>
            <div>
              <div className="relationship-hero-card__meta-tags">
                <span className="relationship-date-badge">{formatDate(r.effectiveFrom)}</span>
              </div>
              <h1 className="relationship-hero-card__title">{t("party.relationship.title").text}</h1>
            </div>
          </div>
          <div className="relationship-hero-card__status">
            <StateChip state={relationshipChip(r, today)} label={t(`party.relationship.status.${r.status}`).text} />
          </div>
        </div>

        {/* Partners Flow Section */}
        <div className="relationship-partners-flow">
          {/* Seller Card */}
          <div className="relationship-partner-tile relationship-partner-tile--seller">
            <div className="relationship-partner-tile__badge">
              <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M3 21h18" />
                <path d="M5 21V7l8-4v18" />
                <path d="M19 21V11l-6-4" />
                <path d="M9 9h1" />
                <path d="M9 13h1" />
                <path d="M9 17h1" />
              </svg>
              <span>{t("party.relationship.seller").text}</span>
            </div>
            <div className="relationship-partner-tile__name">
              <PartyName entityId={r.sellerEntityId} />
            </div>
          </div>

          {/* Connection Indicator */}
          <div className="relationship-partners-connector" aria-hidden="true">
            <div className="relationship-partners-connector__pill">
              <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2.5" fill="none" strokeLinecap="round" strokeLinejoin="round">
                <path d="M5 12h14" />
                <path d="m13 6 6 6-6 6" />
              </svg>
            </div>
          </div>

          {/* Buyer Card */}
          <div className="relationship-partner-tile relationship-partner-tile--buyer">
            <div className="relationship-partner-tile__badge">
              <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7" />
                <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
                <path d="M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4" />
                <path d="M2 7h20" />
              </svg>
              <span>{t("party.relationship.buyer").text}</span>
            </div>
            <div className="relationship-partner-tile__name">
              <PartyName entityId={r.buyerEntityId} />
            </div>
          </div>
        </div>

        {/* Commercial Terms Metrics Bar */}
        <div className="relationship-terms-grid">
          {/* Credit Limit Metric */}
          <div className="relationship-term-card">
            <div className="relationship-term-card__header">
              <div className="relationship-term-card__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
                  <rect width="20" height="14" x="2" y="5" rx="2" />
                  <line x1="2" x2="22" y1="10" y2="10" />
                </svg>
              </div>
              <span className="relationship-term-card__label">{t("party.relationship.credit_limit").text}</span>
            </div>
            <div className="relationship-term-card__value">
              <MoneyDisplay amount={r.creditLimit} />
            </div>
          </div>

          {/* Payment Terms Metric */}
          <div className="relationship-term-card">
            <div className="relationship-term-card__header">
              <div className="relationship-term-card__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10" />
                  <polyline points="12 6 12 12 16 14" />
                </svg>
              </div>
              <span className="relationship-term-card__label">{t("party.relationship.terms_days").text}</span>
            </div>
            <div className="relationship-term-card__value">
              {r.paymentTermsDays !== null && r.paymentTermsDays !== undefined ? `${r.paymentTermsDays} Days` : "—"}
            </div>
          </div>

          {/* Validity Period Metric */}
          <div className="relationship-term-card">
            <div className="relationship-term-card__header">
              <div className="relationship-term-card__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
                  <rect width="18" height="18" x="3" y="4" rx="2" ry="2" />
                  <line x1="16" x2="16" y1="2" y2="6" />
                  <line x1="8" x2="8" y1="2" y2="6" />
                  <line x1="3" x2="21" y1="10" y2="10" />
                </svg>
              </div>
              <span className="relationship-term-card__label">{t("party.relationship.effective_from").text}</span>
            </div>
            <div className="relationship-term-card__value">
              {formatDate(r.effectiveFrom)}
              <span className="relationship-term-card__subvalue">
                {r.effectiveTo ? ` — ${formatDate(r.effectiveTo)}` : " — Present"}
              </span>
            </div>
          </div>
        </div>
      </section>

      {mayChange && (
        <section className="relationship-amend-card">
          <div className="relationship-amend-card__header">
            <div className="relationship-amend-card__icon-badge" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                stroke="currentColor"
                strokeWidth="2"
                fill="none"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M12 20h9" />
                <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
              </svg>
            </div>
            <div className="relationship-amend-card__titles">
              <h2 className="relationship-amend-card__title">{t("party.relationship.limit.title").text}</h2>
              <p className="relationship-amend-card__subtitle">{t("party.relationship.limit.note").text}</p>
            </div>
          </div>
          <div className="relationship-amend-grid">
            <label className="relationship-field">
              <span className="relationship-field__label">{t("party.relationship.limit.new").text}</span>
              <input
                className="relationship-field__input"
                type="text"
                inputMode="decimal"
                value={limit}
                onChange={(event) => setLimit(event.target.value)}
              />
            </label>
            <div className="relationship-field">
              <DatePicker
                id="relationship-effective-from"
                label={t("party.relationship.limit.from").text}
                value={from}
                min={firstDay}
                onChange={(val) => setEffectiveFrom(val)}
              />
            </div>
          </div>
          <div className="relationship-amend-actions">
            <button
              type="button"
              className="action-button--primary relationship-amend-btn"
              disabled={amend.isPending || !limitReady(limit, from)}
              onClick={() => setAsking(true)}
            >
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2.5" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              {t("party.relationship.limit.change").text}
            </button>
          </div>
        </section>
      )}
      {asking && (
        <ReasonCapture
          title={t("party.relationship.limit.question").text}
          codes={LIMIT_REASONS.map((code) => ({ code, label: t(`party.relationship.reason.${code}`).text }))}
          pending={amend.isPending}
          onCancel={() => setAsking(false)}
          onConfirm={(code, text) => amend.mutate({ code, text })}
        />
      )}
      {amend.isError && <p role="alert">{errorText(amend.error, t("party.error.generic").text)}</p>}

      <section className="relationship-history-section">
        <div className="relationship-history-header">
          <div className="relationship-history-header__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="10" />
              <polyline points="12 6 12 12 16 14" />
            </svg>
          </div>
          <h2 className="relationship-section-title">{t("party.relationship.history").text}</h2>
          <span className="relationship-history-count">{history.length}</span>
        </div>
        <div className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th scope="col">{t("party.relationship.effective_from").text}</th>
                  <th scope="col">{t("party.relationship.effective_to").text}</th>
                  <th scope="col">{t("party.col.status").text}</th>
                  <th scope="col" className="table-numeric-head">{t("party.relationship.credit_limit").text}</th>
                  <th scope="col" className="table-numeric-head">{t("party.relationship.terms_days").text}</th>
                </tr>
              </thead>
              <tbody>
                {history.map((h) => (
                  <tr key={h.relationshipId} className={h.relationshipId === r.relationshipId ? "relationship-row--current" : ""}>
                    <td className="table-primary-text">
                      {h.relationshipId === r.relationshipId ? (
                        <span className="relationship-current-cell">
                          <strong>{formatDate(h.effectiveFrom)}</strong>
                          <span className="relationship-current-badge">{t("party.relationship.current").text}</span>
                        </span>
                      ) : (
                        <Link to={`/party/relationships/${h.relationshipId}`}>{formatDate(h.effectiveFrom)}</Link>
                      )}
                    </td>
                    <td>{h.effectiveTo ? formatDate(h.effectiveTo) : "—"}</td>
                    <td>
                      <StateChip state={relationshipChip(h, today)} label={t(`party.relationship.status.${h.status}`).text} />
                    </td>
                    <td className="numeric-cell">
                      <MoneyDisplay amount={h.creditLimit} />
                    </td>
                    <td className="numeric-cell">{h.paymentTermsDays ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </main>
  );
}
