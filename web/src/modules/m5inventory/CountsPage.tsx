import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { SkuLabel } from "./SkuLabel";
import { countChip } from "./stockControl";
import { errorText } from "./stockView";
import "./inventory.css";

/**
 * Stock counts at a location (25A section 8, "Stocktake sheet", back office; doc 25 flow 6.3): the
 * counts there with their state, a form that schedules and starts a count of everything or of some
 * items, and the lots below zero that the tills oversold (doc 25 flow 6.2), to acknowledge until a
 * count corrects them. The count itself is on its own page.
 */
export function CountsPage() {
  const t = useT();
  const api = useInventoryApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const formatDate = useFormatDate();
  const formatInstant = useFormatInstant();
  const canSchedule = useHasPermission("inv.count.schedule");
  const canRecord = useHasPermission("shop.count.record");
  const scheduleKey = useIdempotencyKey();
  const startKey = useIdempotencyKey();
  const acknowledgeKey = useIdempotencyKey();
  const [locationId, setLocationId] = useState("");
  const [scopeKind, setScopeKind] = useState<"FULL" | "SKUS">("SKUS");
  const [picked, setPicked] = useState<string[]>([]);
  const [acknowledging, setAcknowledging] = useState<string | null>(null);

  const counts = useQuery({
    queryKey: ["inventory", "counts", locationId],
    queryFn: () => api.counts(locationId),
    enabled: locationId !== ""
  });
  const lots = useQuery({
    queryKey: ["inventory", "balances", locationId],
    queryFn: () => api.balances(locationId),
    enabled: locationId !== ""
  });
  const negative = useQuery({
    queryKey: ["inventory", "negative-lots", locationId],
    queryFn: () => api.negativeLots(locationId),
    enabled: locationId !== ""
  });
  const skuIds = [...new Set((lots.data ?? []).map((lot) => lot.skuId))];

  const start = useMutation({
    mutationFn: async () => {
      const scheduled = await api.scheduleCount(
        { locationId, scopeKind, skuIds: scopeKind === "SKUS" ? picked : [] },
        scheduleKey.current()
      );
      scheduleKey.next();
      await api.startCount(scheduled.taskId, startKey.current());
      startKey.next();
      return scheduled.taskId;
    },
    onSuccess: (taskId) => {
      queryClient.invalidateQueries({ queryKey: ["inventory"] });
      navigate(`/inventory/counts/${taskId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        scheduleKey.next();
        startKey.next();
      }
    }
  });
  const acknowledge = useMutation({
    mutationFn: ({ stockLotId, reason }: { stockLotId: string; reason: string }) =>
      api.acknowledgeNegativeLot(stockLotId, reason, acknowledgeKey.current()),
    onSuccess: () => {
      acknowledgeKey.next();
      setAcknowledging(null);
      queryClient.invalidateQueries({ queryKey: ["inventory", "negative-lots"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        acknowledgeKey.next();
      }
    }
  });

  const toggle = (skuId: string) =>
    setPicked(picked.includes(skuId) ? picked.filter((id) => id !== skuId) : [...picked, skuId]);
  const ready = locationId !== "" && (scopeKind === "FULL" || picked.length > 0);

  return (
    <main className="shell-page">
      <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      <h1>{t("inventory.count.title").text}</h1>
      <LocationPicker value={locationId} onChange={setLocationId} />

      {canSchedule && canRecord && locationId !== "" && (
        <section className="inventory-section">
          <h2>{t("inventory.count.start_section").text}</h2>
          <label className="inventory-form-field">
            {t("inventory.count.scope").text}
            <select
              aria-label={t("inventory.count.scope").text}
              value={scopeKind}
              onChange={(event) => setScopeKind(event.target.value as "FULL" | "SKUS")}
            >
              <option value="SKUS">{t("inventory.count.scope.SKUS").text}</option>
              <option value="FULL">{t("inventory.count.scope.FULL").text}</option>
            </select>
          </label>
          {scopeKind === "SKUS" && (
            <fieldset className="inventory-form-row">
              <legend>{t("inventory.count.pick_items").text}</legend>
              {skuIds.length === 0 && <p>{t("inventory.balances.empty").text}</p>}
              {skuIds.map((skuId) => (
                <label key={skuId}>
                  <input type="checkbox" checked={picked.includes(skuId)} onChange={() => toggle(skuId)} />{" "}
                  <SkuLabel skuId={skuId} />
                </label>
              ))}
            </fieldset>
          )}
          <div className="inventory-action-bar">
            <button type="button" disabled={!ready || start.isPending} onClick={() => start.mutate()}>
              {t("inventory.count.start").text}
            </button>
          </div>
          {start.isError && <p role="alert">{errorText(start.error, t("inventory.error.generic").text)}</p>}
        </section>
      )}

      <h2>{t("inventory.count.list").text}</h2>
      {counts.isError && <p role="alert">{errorText(counts.error, t("inventory.error.generic").text)}</p>}
      {counts.data?.length === 0 && <p>{t("inventory.count.none").text}</p>}
      {counts.data && counts.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t("inventory.count.scheduled_for").text}</th>
              <th>{t("inventory.count.scope").text}</th>
              <th>{t("inventory.repack.column.status").text}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {counts.data.map((count) => (
              <tr key={count.taskId}>
                <td>{formatDate(count.scheduledFor)}</td>
                <td>{t(`inventory.count.scope.${count.scopeKind}`).text}</td>
                <td>
                  <StateChip state={countChip(count.status)} label={t(`inventory.count.status.${count.status}`).text} />
                  {count.outcome && ` ${t(`inventory.count.outcome.${count.outcome}`).text}`}
                </td>
                <td>
                  <Link to={`/inventory/counts/${count.taskId}`}>{t("inventory.count.open").text}</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>{t("inventory.negative.title").text}</h2>
      {negative.data?.length === 0 && <p>{t("inventory.negative.none").text}</p>}
      {negative.data && negative.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t("inventory.column.item").text}</th>
              <th>{t("inventory.column.batch").text}</th>
              <th>{t("inventory.column.on_hand").text}</th>
              <th>{t("inventory.negative.since").text}</th>
              <th>{t("inventory.negative.acknowledged").text}</th>
            </tr>
          </thead>
          <tbody>
            {negative.data.map((lot) => (
              <tr key={lot.stockLotId}>
                <td>
                  <SkuLabel skuId={lot.skuId} />
                </td>
                <td>{lot.batchNo ?? ""}</td>
                <td className="numeric-cell">{lot.qtyOnHand}</td>
                <td>{lot.negativeSince ? formatInstant(lot.negativeSince) : ""}</td>
                <td>
                  {lot.acknowledgedAt ? (
                    formatInstant(lot.acknowledgedAt)
                  ) : canSchedule ? (
                    <button type="button" onClick={() => setAcknowledging(lot.stockLotId)}>
                      {t("inventory.negative.acknowledge").text}
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {acknowledging && (
        <ReasonCapture
          title={t("inventory.negative.question").text}
          codes={["OVERSOLD_OFFLINE", "RECEIPT_NOT_RECORDED", "OTHER"].map((code) => ({
            code,
            label: t(`inventory.negative.reason.${code}`).text
          }))}
          pending={acknowledge.isPending}
          onCancel={() => setAcknowledging(null)}
          onConfirm={(code, text) =>
            acknowledge.mutate({
              stockLotId: acknowledging,
              reason: [t(`inventory.negative.reason.${code}`).text, text].filter(Boolean).join(": ")
            })
          }
        />
      )}
      {acknowledge.isError && <p role="alert">{errorText(acknowledge.error, t("inventory.error.generic").text)}</p>}
    </main>
  );
}
