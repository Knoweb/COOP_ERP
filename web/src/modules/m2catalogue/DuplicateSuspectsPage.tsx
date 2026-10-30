import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { PageHeader } from "../../shell/components/PageHeader";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApiProblem } from "../../shell/api/client";
import { useCatalogueApi } from "./catalogueApi";
import { errorText } from "./skuView";


type FilterStatus = "OPEN" | "MERGED" | "DISMISSED";

/**
 * Catalogue Duplicate Suspects Page (CAT-01, 22A section 8, "Duplicate suspects").
 * Displays duplicate suspects flagged by the scoring engine and lets stewards resolve them.
 */
export function DuplicateSuspectsPage() {
  const t = useT();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const canResolve = useHasPermission("cat.sku.create_local");
  const resolveKey = useIdempotencyKey();

  const [filter, setFilter] = useState<FilterStatus>("OPEN");
  const [successMsg, setSuccessMsg] = useState("");

  const suspectsQuery = useQuery({
    queryKey: ["catalogue", "steward", "suspects", filter],
    queryFn: () => api.listDuplicateSuspects(filter)
  });

  const resolveMutation = useMutation({
    mutationFn: ({ suspectId, decision }: { suspectId: string; decision: "MERGED" | "DISMISSED" }) =>
      api.resolveDuplicateSuspect(
        suspectId,
        { decision },
        resolveKey.current()
      ),
    onSuccess: () => {
      resolveKey.next();
      setSuccessMsg(t("catalogue.suspects.resolved_success").text);
      queryClient.invalidateQueries({ queryKey: ["catalogue", "steward", "suspects"] });
      queryClient.invalidateQueries({ queryKey: ["catalogue", "skus"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        resolveKey.next();
      }
    }
  });

  const getScoreBadgeClass = (score: number) => {
    if (score >= 0.95) return "score-badge--critical";
    if (score >= 0.85) return "score-badge--high";
    return "score-badge--moderate";
  };

  const suspects = suspectsQuery.data ?? [];

  return (
    <main className="shell-page">
      <div style={{ marginBottom: "var(--space-2)" }}>
        <Link className="back-pill-btn" to="/catalogue">
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
          {t("catalogue.back").text}
        </Link>
      </div>

      <PageHeader
        icon="catalogue"
        title={t("catalogue.suspects.title").text}
      />
      <p className="catalogue-import-subtitle">{t("catalogue.suspects.description").text}</p>

      {/* Filter Tabs */}
      <div className="suspects-tabs" role="tablist">
        {(["OPEN", "MERGED", "DISMISSED"] as FilterStatus[]).map((st) => (
          <button
            key={st}
            type="button"
            role="tab"
            aria-selected={filter === st}
            className={`suspects-tab ${filter === st ? "suspects-tab--active" : ""}`}
            onClick={() => {
              setFilter(st);
              setSuccessMsg("");
            }}
          >
            {t(`catalogue.suspects.filter.${st}`).text}
          </button>
        ))}
      </div>

      {successMsg && (
        <div className="import-alert import-alert--success" role="status">
          <span>{successMsg}</span>
        </div>
      )}

      {resolveMutation.isError && (
        <p role="alert" className="error-text">
          {errorText(resolveMutation.error, t("catalogue.error.generic").text)}
        </p>
      )}

      {/* Suspects List */}
      <section className="catalogue-section">
        {suspectsQuery.isLoading && <p>{t("catalogue.loading").text}</p>}

        {suspects.length === 0 && !suspectsQuery.isLoading && (
          <p className="import-empty-state">{t("catalogue.suspects.empty").text}</p>
        )}

        {suspects.length > 0 && (
          <div className="suspects-grid">
            {suspects.map((suspect) => (
              <div key={suspect.suspectId} className="suspect-card">
                <div className="suspect-card__header">
                  <span className={`score-badge ${getScoreBadgeClass(suspect.score)}`}>
                    {(suspect.score * 100).toFixed(0)}% {t("catalogue.suspects.score").text}
                  </span>
                  <span className={`suspect-card__status suspect-card__status--${suspect.status.toLowerCase()}`}>
                    {suspect.status}
                  </span>
                </div>

                <div className="suspect-card__comparison">
                  <div className="suspect-card__side">
                    <h4>{t("catalogue.suspects.candidate").text}</h4>
                    <div className="suspect-card__sku">
                      <Link to={`/catalogue/skus/${suspect.skuA}`}>
                        {suspect.skuACode}
                      </Link>
                      <span className="suspect-card__name">{suspect.skuAName}</span>
                    </div>
                  </div>

                  <div className="suspect-card__divider">⇄</div>

                  <div className="suspect-card__side">
                    <h4>{t("catalogue.suspects.matched").text}</h4>
                    <div className="suspect-card__sku">
                      <Link to={`/catalogue/skus/${suspect.skuB}`}>
                        {suspect.skuBCode}
                      </Link>
                      <span className="suspect-card__name">{suspect.skuBName}</span>
                    </div>
                  </div>
                </div>

                {suspect.reasons && suspect.reasons.length > 0 && (
                  <div className="reasons-list">
                    {suspect.reasons.map((r, idx) => (
                      <span key={idx} className="reason-tag">
                        {r}
                      </span>
                    ))}
                  </div>
                )}

                {suspect.status === "OPEN" && canResolve && (
                  <div className="suspect-card__actions">
                    <button
                      type="button"
                      className="action-button action-button--primary"
                      disabled={resolveMutation.isPending}
                      onClick={() =>
                        resolveMutation.mutate({
                          suspectId: suspect.suspectId,
                          decision: "MERGED"
                        })
                      }
                    >
                      {t("catalogue.suspects.merge").text}
                    </button>
                    <button
                      type="button"
                      className="action-button action-button--secondary"
                      disabled={resolveMutation.isPending}
                      onClick={() =>
                        resolveMutation.mutate({
                          suspectId: suspect.suspectId,
                          decision: "DISMISSED"
                        })
                      }
                    >
                      {t("catalogue.suspects.dismiss").text}
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}
