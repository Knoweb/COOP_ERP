import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { locationText } from "../../shell/i18n/localName";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi, type Location, type Transfer } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import "./inventory.css";
import { SkuLabel } from "./SkuLabel";
import { errorText, transferLinesOf } from "./stockView";
import { PageHeader } from "../../shell/components/PageHeader";

/**
 * Transfers between two locations of the entity (doc 25 flow 6.6; 25A section 8, "Transfer
 * note", demo scope). An entity-wide user (`inv.transfer.issue`) sends stock from the chosen
 * location: a quantity against each of its GOOD lots, and a destination. The destination's own
 * session (`shop.transfer.receive`) receives what is in transit to it; each side writes only
 * its own rows (the server's rule, PR #148).
 */
export function TransfersPage() {
  const t = useT();
  const { locale } = useIntl();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const canIssue = useHasPermission("inv.transfer.issue");
  const canReceive = useHasPermission("shop.transfer.receive");
  const issueKey = useIdempotencyKey();
  const receiveKey = useIdempotencyKey();
  const [locationId, setLocationId] = useState("");
  const [toLocationId, setToLocationId] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>({});

  const locations = useQuery({ queryKey: ["inventory", "locations"], queryFn: () => api.locations(), staleTime: Infinity });
  const transfers = useQuery({
    queryKey: ["inventory", "transfers", locationId],
    queryFn: () => api.transfers(locationId),
    enabled: locationId !== ""
  });
  const lots = useQuery({
    queryKey: ["inventory", "balances", locationId],
    queryFn: () => api.balances(locationId),
    enabled: locationId !== "" && canIssue
  });
  const sendable = (lots.data ?? []).filter((lot) => lot.condition === "GOOD" && lot.qtyOnHand > 0);
  const destinations = (locations.data ?? []).filter((l) => l.locationId !== locationId);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["inventory"] });
  const forget = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };
  const issue = useMutation({
    mutationFn: () =>
      api.issueTransfer(
        { fromLocationId: locationId, toLocationId, lines: transferLinesOf(sendable, quantities) },
        issueKey.current()
      ),
    onSuccess: () => {
      issueKey.next();
      setQuantities({});
      refresh();
    },
    onError: forget(issueKey)
  });
  const receive = useMutation({
    mutationFn: (transferId: string) => api.receiveTransfer(transferId, receiveKey.current()),
    onSuccess: () => {
      receiveKey.next();
      refresh();
    },
    onError: forget(receiveKey)
  });

  const nameOf = (id: string) => {
    const found: Location | undefined = (locations.data ?? []).find((l) => l.locationId === id);
    return found ? locationText(found, locale) : id.slice(0, 8);
  };
  const lineCount = transferLinesOf(sendable, quantities).length;

  return (
    <main className="shell-page">
      <PageHeader
        icon="stock"
        title={t("inventory.transfer.title").text}
        actions={
          <Link className="action-link" to="/inventory">
            <span>{t("inventory.back").text}</span>
          </Link>
        }
      />
      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>
      </section>

      <section className="modern-table-card">
        <div style={{ padding: 'var(--space-4) var(--space-4) 0' }}>
          <h2>{t("inventory.transfer.list").text}</h2>
        </div>
      {transfers.data?.length === 0 && <p style={{ padding: '0 var(--space-4) var(--space-4)' }}>{t("inventory.transfer.none").text}</p>}
      {(transfers.data ?? []).map((transfer: Transfer) => (
        <section key={transfer.transferId} className="inventory-section" style={{ padding: '0 var(--space-4) var(--space-4)' }}>
          <p>
            {`${nameOf(transfer.fromLocationId)} → ${nameOf(transfer.toLocationId)} `}
            <StateChip
              state={transfer.status === "RECEIVED" ? "issued" : "draft"}
              label={t(`inventory.transfer.status.${transfer.status}`).text}
            />
          </p>
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
            <thead>
              <tr>
                <th>{t("inventory.column.item").text}</th>
                <th>{t("inventory.column.qty").text}</th>
              </tr>
            </thead>
            <tbody>
              {transfer.lines.map((line) => (
                <tr key={line.lineNo}>
                  <td>
                    <SkuLabel skuId={line.skuId} />
                  </td>
                  <td>{line.qty}</td>
                </tr>
              ))}
            </tbody>
            </table>
          </div>
          {transfer.status === "IN_TRANSIT" && transfer.toLocationId === locationId && canReceive && (
            <button type="button" disabled={receive.isPending} onClick={() => receive.mutate(transfer.transferId)}>
              {t("inventory.transfer.receive").text}
            </button>
          )}
        </section>
      ))}
      {receive.isError && <p role="alert" style={{ padding: '0 var(--space-4)' }}>{errorText(receive.error, t("inventory.error.generic").text)}</p>}
      </section>

      {canIssue && !locations.isError && (
        <section className="modern-table-card inventory-section" style={{ padding: 'var(--space-4)', marginTop: 'var(--space-4)' }}>
          <h2>{t("inventory.transfer.send").text}</h2>
          <label className="inventory-form-field">
            {t("inventory.transfer.destination").text}
            <select value={toLocationId} onChange={(event) => setToLocationId(event.target.value)}>
              <option value="" />
              {destinations.map((l) => (
                <option key={l.locationId} value={l.locationId}>
                  {locationText(l, locale)}
                </option>
              ))}
            </select>
          </label>
          {sendable.length === 0 && <p>{t("inventory.balances.empty").text}</p>}
          {sendable.length > 0 && (
            <div className="modern-table-scroll" style={{ marginTop: 'var(--space-3)' }}>
              <table className="modern-table stock-table">
              <thead>
                <tr>
                  <th>{t("inventory.column.item").text}</th>
                  <th>{t("inventory.column.batch").text}</th>
                  <th>{t("inventory.column.on_hand").text}</th>
                  <th>{t("inventory.transfer.qty_to_send").text}</th>
                </tr>
              </thead>
              <tbody>
                {sendable.map((lot) => (
                  <tr key={lot.stockLotId}>
                    <td>
                      <SkuLabel skuId={lot.skuId} />
                    </td>
                    <td>{lot.batchNo ?? ""}</td>
                    <td>{lot.qtyOnHand}</td>
                    <td>
                      <input
                        inputMode="decimal"
                        aria-label={t("inventory.transfer.qty_to_send").text}
                        value={quantities[lot.batchId] ?? ""}
                        onChange={(event) => setQuantities({ ...quantities, [lot.batchId]: event.target.value })}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
              </table>
            </div>
          )}
          <button
            type="button"
            disabled={issue.isPending || toLocationId === "" || lineCount === 0}
            onClick={() => issue.mutate()}
          >
            {t("inventory.transfer.issue").text}
          </button>
          {issue.isError && <p role="alert">{errorText(issue.error, t("inventory.error.generic").text)}</p>}
        </section>
      )}
    </main>
  );
}
