import { useState } from "react";
import { useIntl } from "react-intl";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { openServerFile } from "../../shell/api/openServerFile";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useT } from "../../shell/i18n/useT";
import { inLocale } from "../../shell/i18n/localName";
import { errorText } from "./priceListState";
import { usePricingApi } from "./pricingApi";

/** The PRC-03 action for the currently effective shelf list. */
export function ShelfLabelPrintAction({ skuIds }: { skuIds: string[] }) {
  const canPrint = useHasPermission("prc.label.print");
  const t = useT();
  const { locale } = useIntl();
  const api = usePricingApi();
  const key = useIdempotencyKey();
  const [locationId, setLocationId] = useState("");
  const [jobId, setJobId] = useState<string | null>(null);

  const locations = useQuery({
    queryKey: ["pricing", "locations"],
    queryFn: () => api.locations(),
    enabled: canPrint
  });
  const job = useQuery({
    queryKey: ["pricing", "labelJob", jobId],
    queryFn: () => api.labelJob(jobId!),
    enabled: jobId !== null,
    refetchInterval: (query) => (query.state.data?.status === "QUEUED" ? 2_000 : false)
  });
  const request = useMutation({
    mutationFn: () => api.printLabels(locationId, skuIds, key.current()),
    onSuccess: (queued) => {
      key.next();
      setJobId(queued.jobId);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) key.next();
    }
  });
  const open = useMutation({
    mutationFn: () => openServerFile(() => api.labelJob(jobId!).then((fresh) => fresh.downloadUrl))
  });

  if (!canPrint) return null;

  const shops = (locations.data ?? []).filter((location) => location.locationType === "SHOP");
  const result = job.data;

  return (
    <section className="pricing-section" aria-labelledby="pricing-labels-title">
      <h2 id="pricing-labels-title">{t("pricing.labels.title").text}</h2>
      <div className="pricing-form-grid">
        <label className="pricing-form-field">
          {t("pricing.labels.shop").text}
          <select aria-label={t("pricing.labels.shop").text} value={locationId} onChange={(event) => setLocationId(event.target.value)}>
            <option value="">{t("pricing.labels.choose").text}</option>
            {shops.map((shop) => (
              <option key={shop.locationId} value={shop.locationId}>
                {inLocale(locale, shop.nameEn, shop.nameSi, shop.nameTa)}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          disabled={request.isPending || locationId === "" || skuIds.length === 0}
          onClick={() => request.mutate()}
        >
          {t("pricing.labels.print").text}
        </button>
      </div>

      {request.isError && <p role="alert">{errorText(request.error, t("pricing.error.generic").text)}</p>}
      {(request.isPending || result?.status === "QUEUED") && <p role="status">{t("pricing.labels.queued").text}</p>}
      {result?.status === "FAILED" && <p role="alert">{t("pricing.labels.failed").text}</p>}
      {result?.status === "RENDERED" && (
        <div aria-live="polite">
          <p>{t("pricing.labels.rendered", undefined, { count: result.renderedCount }).text}</p>
          <button type="button" disabled={open.isPending} onClick={() => open.mutate()}>
            {t("pricing.labels.open").text}
          </button>
          {open.isError && <p role="alert">{t("pricing.labels.open_failed").text}</p>}
          {result.refused.length > 0 && (
            <>
              <p>{t("pricing.labels.refused", undefined, { count: result.refused.length }).text}</p>
              <div className="modern-table-card">
                <div className="modern-table-scroll">
                  <table className="modern-table">
                    <thead>
                      <tr>
                        <th>{t("pricing.labels.column.item").text}</th>
                        <th>{t("pricing.labels.column.list_price").text}</th>
                        <th>{t("pricing.labels.column.ceiling").text}</th>
                        <th>{t("pricing.labels.column.reason").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {result.refused.map((refusal) => (
                        <tr key={refusal.skuId}>
                          <td>{refusal.skuCode}</td>
                          <td><MoneyDisplay amount={refusal.listPrice} /></td>
                          <td>
                            <MoneyDisplay amount={refusal.ceilingPrice} />
                            {refusal.ceilingReference ? ` (${refusal.ceilingReference})` : ""}
                          </td>
                          <td>{t("pricing.labels.reason.above_control_price").text}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </section>
  );
}
