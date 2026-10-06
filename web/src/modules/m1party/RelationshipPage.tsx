import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { useScope } from "../../shell/scope/useScope";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
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
    return <main className="shell-page">{t("party.list.loading").text}</main>;
  }
  if (row.isError || !row.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(row.error, t("party.error.generic").text)}</p>
        <Link className="back-link" to="/party/relationships">
          {t("party.relationship.back").text}
        </Link>
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
    <main className="shell-page">
      <Link className="back-link" to="/party/relationships">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("party.relationship.back").text}
      </Link>
      <DocumentHeader
        code={formatDate(r.effectiveFrom)}
        title={t("party.relationship.title").text}
        state={{ look: relationshipChip(r, today), label: t(`party.relationship.status.${r.status}`).text }}
        facts={[
          { label: t("party.relationship.seller").text, value: <PartyName entityId={r.sellerEntityId} /> },
          { label: t("party.relationship.buyer").text, value: <PartyName entityId={r.buyerEntityId} /> },
          { label: t("party.relationship.credit_limit").text, value: <MoneyDisplay amount={r.creditLimit} size="total" /> },
          { label: t("party.relationship.terms_days").text, value: r.paymentTermsDays ?? undefined },
          { label: t("party.relationship.effective_from").text, value: formatDate(r.effectiveFrom) },
          { label: t("party.relationship.effective_to").text, value: r.effectiveTo ? formatDate(r.effectiveTo) : undefined }
        ]}
      />

      {mayChange && (
        <section className="party-form-row-margin">
          <h2>{t("party.relationship.limit.title").text}</h2>
          <label className="party-form-field">
            {t("party.relationship.limit.new").text}
            <input type="text" inputMode="decimal" value={limit} onChange={(event) => setLimit(event.target.value)} />
          </label>
          <label className="party-form-field">
            {t("party.relationship.limit.from").text}
            <input type="date" min={firstDay} value={from} onChange={(event) => setEffectiveFrom(event.target.value)} />
          </label>
          <p>{t("party.relationship.limit.note").text}</p>
          <div>
            <button type="button" disabled={amend.isPending || !limitReady(limit, from)} onClick={() => setAsking(true)}>
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

      <section>
        <h2>{t("party.relationship.history").text}</h2>
        <table className="party-table-full">
          <thead>
            <tr>
              <th>{t("party.relationship.effective_from").text}</th>
              <th>{t("party.relationship.effective_to").text}</th>
              <th>{t("party.col.status").text}</th>
              <th>{t("party.relationship.credit_limit").text}</th>
              <th>{t("party.relationship.terms_days").text}</th>
            </tr>
          </thead>
          <tbody>
            {history.map((h) => (
              <tr key={h.relationshipId}>
                <td>
                  {h.relationshipId === r.relationshipId ? (
                    formatDate(h.effectiveFrom)
                  ) : (
                    <Link to={`/party/relationships/${h.relationshipId}`}>{formatDate(h.effectiveFrom)}</Link>
                  )}
                </td>
                <td>{h.effectiveTo ? formatDate(h.effectiveTo) : ""}</td>
                <td>
                  <StateChip state={relationshipChip(h, today)} label={t(`party.relationship.status.${h.status}`).text} />
                </td>
                <td>
                  <MoneyDisplay amount={h.creditLimit} />
                </td>
                <td>{h.paymentTermsDays ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </main>
  );
}
