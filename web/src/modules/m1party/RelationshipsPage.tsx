import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { usePartyApi } from "./partyApi";
import { errorText } from "./SocietyRegisterPage";
import { PartyName } from "./PartyName";
import { latestPerPair, relationshipChip } from "./relationshipView";

/**
 * The relationships in which the caller's entity sells (21A section 8, the Relationships tab):
 * one row per buyer, its latest terms, each opening the agreement sheet where the credit limit
 * is changed and the history read.
 */
export function RelationshipsPage() {
  const t = useT();
  const formatDate = useFormatDate();
  const api = usePartyApi();
  const today = businessToday();
  const rows = useQuery({ queryKey: ["party", "relationships", "SELLER"], queryFn: () => api.listRelationships("SELLER") });
  const latest = latestPerPair(rows.data ?? []);

  return (
    <main className="shell-page">
      <PageHeader
        icon="relationships"
        title={t("party.relationships.title").text}
      />

      {rows.isLoading && (
        <div className="catalogue-state-card catalogue-state-card--loading">
          <div className="catalogue-state-spinner" aria-hidden="true" />
          <p>{t("party.list.loading").text}</p>
        </div>
      )}

      {rows.isError && (
        <div className="catalogue-state-card catalogue-state-card--error" role="alert">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          <p>{errorText(rows.error, t("party.error.generic").text)}</p>
        </div>
      )}

      {rows.isSuccess && latest.length === 0 && (
        <div className="party-empty-card">
          <p className="party-empty-text">{t("party.relationships.empty").text}</p>
        </div>
      )}

      {latest.length > 0 && (
        <div className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th scope="col">{t("party.relationship.buyer").text}</th>
                  <th scope="col">{t("party.col.status").text}</th>
                  <th scope="col" className="table-numeric-head">{t("party.relationship.credit_limit").text}</th>
                  <th scope="col" className="table-numeric-head">{t("party.relationship.terms_days").text}</th>
                  <th scope="col">{t("party.relationship.effective_from").text}</th>
                </tr>
              </thead>
              <tbody>
                {latest.map((row) => (
                  <tr key={row.relationshipId}>
                    <td className="table-primary-text">
                      <Link to={`/party/relationships/${row.relationshipId}`}>
                        <PartyName entityId={row.buyerEntityId} />
                      </Link>
                    </td>
                    <td>
                      <StateChip state={relationshipChip(row, today)} label={t(`party.relationship.status.${row.status}`).text} />
                    </td>
                    <td className="numeric-cell">
                      <MoneyDisplay amount={row.creditLimit} />
                    </td>
                    <td className="numeric-cell">{row.paymentTermsDays ?? "—"}</td>
                    <td>{formatDate(row.effectiveFrom)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </main>
  );
}
