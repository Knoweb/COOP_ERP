import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { locationText } from "../../shell/i18n/localName";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { StateChip } from "../../shell/components/StateChip";
import {
  useInventoryApi,
  type Location,
  type Transfer,
  type CancelTransferRequest
} from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import "./inventory.css";
import { SkuLabel } from "./SkuLabel";
import { errorText, transferLinesOf, transferChip } from "./stockView";
import { PageHeader } from "../../shell/components/PageHeader";

/**
 * Transfers between two locations of the entity (doc 25 flow 6.6; 25A section 8, "Transfer
 * note", demo scope). An entity-wide user (`inv.transfer.issue`) sends stock from the chosen
 * location: a quantity against each of its GOOD lots, and a destination. The source dispatches
 * the transfer (`inv.transfer.issue`), transitioning it to IN_TRANSIT. The destination's own
 * session (`shop.transfer.receive`) receives what is in transit to it; each side writes only
 * its own rows (the server's rule, PR #148). Unreceived transfers may be cancelled with a reason.
 */
export function TransfersPage() {
  const t = useT();
  const { locale } = useIntl();
  const formatInstant = useFormatInstant();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const canIssue = useHasPermission("inv.transfer.issue");
  const canReceive = useHasPermission("shop.transfer.receive");
  const issueKey = useIdempotencyKey();
  const dispatchKey = useIdempotencyKey();
  const receiveKey = useIdempotencyKey();
  const cancelKey = useIdempotencyKey();

  const [locationId, setLocationId] = useState("");
  const [toLocationId, setToLocationId] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [filter, setFilter] = useState<"all" | "outbound" | "inbound">("all");

  const [cancellingTransferId, setCancellingTransferId] = useState<string | null>(null);
  const [cancelReason, setCancelReason] = useState("");
  const [physicalReturnVerified, setPhysicalReturnVerified] = useState(false);

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

  const dispatch = useMutation({
    mutationFn: (transferId: string) => api.dispatchTransfer(transferId, dispatchKey.current()),
    onSuccess: () => {
      dispatchKey.next();
      refresh();
    },
    onError: forget(dispatchKey)
  });

  const receive = useMutation({
    mutationFn: (transferId: string) => api.receiveTransfer(transferId, receiveKey.current()),
    onSuccess: () => {
      receiveKey.next();
      refresh();
    },
    onError: forget(receiveKey)
  });

  const cancel = useMutation({
    mutationFn: ({ transferId, request }: { transferId: string; request: CancelTransferRequest }) =>
      api.cancelTransfer(transferId, request, cancelKey.current()),
    onSuccess: () => {
      cancelKey.next();
      setCancellingTransferId(null);
      setCancelReason("");
      setPhysicalReturnVerified(false);
      refresh();
    },
    onError: forget(cancelKey)
  });

  const nameOf = (id: string) => {
    const found: Location | undefined = (locations.data ?? []).find((l) => l.locationId === id);
    return found ? locationText(found, locale) : id.slice(0, 8);
  };
  const lineCount = transferLinesOf(sendable, quantities).length;

  const allTransfers = transfers.data ?? [];
  const filteredTransfers = allTransfers.filter((transfer) => {
    if (filter === "outbound") {
      return transfer.fromLocationId === locationId;
    }
    if (filter === "inbound") {
      return transfer.toLocationId === locationId;
    }
    return true;
  });

  const openCancel = (transfer: Transfer) => {
    setCancellingTransferId(transfer.transferId);
    setCancelReason("");
    setPhysicalReturnVerified(false);
  };

  const closeCancel = () => {
    setCancellingTransferId(null);
    setCancelReason("");
    setPhysicalReturnVerified(false);
  };

  return (
    <main className="shell-page">
      <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      <PageHeader
        icon="stock"
        title={t("inventory.transfer.title").text}
      />
      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>
      </section>

      <section className="modern-table-card">
        <div style={{ padding: 'var(--space-4) var(--space-4) var(--space-2)' }}>
          <h2>{t("inventory.transfer.list").text}</h2>
        </div>

        {locationId !== "" && allTransfers.length > 0 && (
          <div style={{ display: 'flex', gap: 'var(--space-2)', padding: '0 var(--space-4) var(--space-3)' }}>
            <button
              type="button"
              className={`button ${filter === "all" ? "button--primary" : "button--secondary"}`}
              onClick={() => setFilter("all")}
            >
              {t("inventory.transfer.all").text}
            </button>
            <button
              type="button"
              className={`button ${filter === "outbound" ? "button--primary" : "button--secondary"}`}
              onClick={() => setFilter("outbound")}
            >
              {t("inventory.transfer.outbound").text}
            </button>
            <button
              type="button"
              className={`button ${filter === "inbound" ? "button--primary" : "button--secondary"}`}
              onClick={() => setFilter("inbound")}
            >
              {t("inventory.transfer.inbound").text}
            </button>
          </div>
        )}

        {locationId !== "" && filteredTransfers.length === 0 && (
          <p style={{ padding: '0 var(--space-4) var(--space-4)' }}>{t("inventory.transfer.none").text}</p>
        )}

        {filteredTransfers.map((transfer: Transfer) => {
          const isSource = transfer.fromLocationId === locationId;
          const isDest = transfer.toLocationId === locationId;
          const isCancelling = cancellingTransferId === transfer.transferId;

          return (
            <section key={transfer.transferId} className="inventory-section" style={{ padding: '0 var(--space-4) var(--space-4)' }}>
              <p>
                <strong>{`${nameOf(transfer.fromLocationId)} → ${nameOf(transfer.toLocationId)}`}</strong>
                {transfer.documentNo ? ` · ${transfer.documentNo}` : ""}
                {" "}
                <StateChip
                  state={transferChip(transfer.status)}
                  label={t(`inventory.transfer.status.${transfer.status}`).text}
                />
              </p>
              {transfer.issuedAt && (
                <p style={{ fontSize: 'var(--font-size-sm)', color: 'var(--color-text-muted)' }}>
                  {formatInstant(transfer.issuedAt)}
                </p>
              )}
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
                      <td className="col-item" data-label={t("inventory.column.item").text}>
                        <SkuLabel skuId={line.skuId} />
                      </td>
                      <td className="numeric-cell" data-label={t("inventory.column.qty").text}>{line.qty}</td>
                    </tr>
                  ))}
                </tbody>
                </table>
              </div>

              <div style={{ display: 'flex', gap: 'var(--space-2)', marginTop: 'var(--space-3)' }}>
                {transfer.status === "ISSUED" && isSource && canIssue && (
                  <button
                    type="button"
                    disabled={dispatch.isPending}
                    onClick={() => dispatch.mutate(transfer.transferId)}
                  >
                    {t("inventory.transfer.dispatch").text}
                  </button>
                )}
                {transfer.status === "IN_TRANSIT" && isDest && canReceive && (
                  <button
                    type="button"
                    disabled={receive.isPending}
                    onClick={() => receive.mutate(transfer.transferId)}
                  >
                    {t("inventory.transfer.receive").text}
                  </button>
                )}
                {(transfer.status === "ISSUED" || transfer.status === "IN_TRANSIT") && isSource && canIssue && !isCancelling && (
                  <button
                    type="button"
                    className="button--secondary"
                    onClick={() => openCancel(transfer)}
                  >
                    {t("inventory.transfer.cancel").text}
                  </button>
                )}
              </div>

              {isCancelling && (
                <div className="modern-filter-panel" style={{ marginTop: 'var(--space-3)', padding: 'var(--space-3)' }}>
                  <label className="inventory-form-field">
                    {t("inventory.transfer.cancel_reason").text}
                    <input
                      type="text"
                      maxLength={500}
                      value={cancelReason}
                      onChange={(e) => setCancelReason(e.target.value)}
                      placeholder={t("inventory.transfer.cancel_reason").text}
                    />
                  </label>
                  {transfer.status === "IN_TRANSIT" && (
                    <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)', marginTop: 'var(--space-2)' }}>
                      <input
                        type="checkbox"
                        checked={physicalReturnVerified}
                        onChange={(e) => setPhysicalReturnVerified(e.target.checked)}
                      />
                      {t("inventory.transfer.physical_return_verified").text}
                    </label>
                  )}
                  <div style={{ display: 'flex', gap: 'var(--space-2)', marginTop: 'var(--space-3)' }}>
                    <button
                      type="button"
                      disabled={
                        cancel.isPending ||
                        cancelReason.trim() === "" ||
                        (transfer.status === "IN_TRANSIT" && !physicalReturnVerified)
                      }
                      onClick={() =>
                        cancel.mutate({
                          transferId: transfer.transferId,
                          request: {
                            reason: cancelReason.trim(),
                            physicalReturnVerified: transfer.status === "IN_TRANSIT" ? physicalReturnVerified : undefined
                          }
                        })
                      }
                    >
                      {t("inventory.transfer.cancel_confirm").text}
                    </button>
                    <button type="button" className="button--secondary" onClick={closeCancel}>
                      {t("inventory.transfer.cancel_close").text}
                    </button>
                  </div>
                </div>
              )}
            </section>
          );
        })}

        {dispatch.isError && (
          <p role="alert" style={{ padding: '0 var(--space-4)' }}>{errorText(dispatch.error, t("inventory.error.generic").text)}</p>
        )}
        {receive.isError && (
          <p role="alert" style={{ padding: '0 var(--space-4)' }}>{errorText(receive.error, t("inventory.error.generic").text)}</p>
        )}
        {cancel.isError && (
          <p role="alert" style={{ padding: '0 var(--space-4)' }}>{errorText(cancel.error, t("inventory.error.generic").text)}</p>
        )}
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
