import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePartyApi } from "./partyApi";
import { errorText } from "./SocietyRegisterPage";
import { PartyName } from "./PartyName";
import { businessToday, latestPerPair, relationshipChip } from "./relationshipView";

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
      <h1>{t("party.relationships.title").text}</h1>
      {rows.isLoading && <p>{t("party.list.loading").text}</p>}
      {rows.isError && <p role="alert">{errorText(rows.error, t("party.error.generic").text)}</p>}
      {rows.isSuccess && latest.length === 0 && <p>{t("party.relationships.empty").text}</p>}
      {latest.length > 0 && (
        <table className="party-table-full">
          <thead>
            <tr>
              <th>{t("party.relationship.buyer").text}</th>
              <th>{t("party.col.status").text}</th>
              <th>{t("party.relationship.credit_limit").text}</th>
              <th>{t("party.relationship.terms_days").text}</th>
              <th>{t("party.relationship.effective_from").text}</th>
            </tr>
          </thead>
          <tbody>
            {latest.map((row) => (
              <tr key={row.relationshipId}>
                <td>
                  <Link to={`/party/relationships/${row.relationshipId}`}>
                    <PartyName entityId={row.buyerEntityId} />
                  </Link>
                </td>
                <td>
                  <StateChip state={relationshipChip(row, today)} label={t(`party.relationship.status.${row.status}`).text} />
                </td>
                <td>
                  <MoneyDisplay amount={row.creditLimit} />
                </td>
                <td>{row.paymentTermsDays ?? ""}</td>
                <td>{formatDate(row.effectiveFrom)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  );
}
