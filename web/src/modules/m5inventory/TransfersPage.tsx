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
import { CustomSelect } from "./CustomSelect";
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
    mutationFn: (transfer: Transfer) =>
      api.receiveTransfer(transfer.transferId, receiveKey.current(), {
        lines: transfer.lines.map((line) => ({
          transferLineId: line.transferLineId,
          receivedQty: line.qty
        }))
      }),
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
      <div>
        <Link className="back-pill-btn" to="/inventory">
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
          {t("inventory.back").text}
        </Link>
      </div>
      <PageHeader
        icon="transfers"
        title={t("inventory.transfer.title").text}
      />
      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>
      </section>

      <section className="modern-table-card">
        <div className="inventory-card-header">
          <span className="inventory-card-header__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
              <path d="M17 3l4 4-4 4" />
              <path d="M3 11v-1a4 4 0 0 1 4-4h14" />
              <path d="M7 21l-4-4 4-4" />
              <path d="M21 13v1a4 4 0 0 1-4 4H3" />
            </svg>
          </span>
          <h2 className="inventory-card-header__title">{t("inventory.transfer.list").text}</h2>
        </div>

        {locationId !== "" && allTransfers.length > 0 && (
          <div className="transfer-filter-tabs">
            <button
              type="button"
              className={`transfer-filter-tab ${filter === "all" ? "transfer-filter-tab--active" : ""}`}
              onClick={() => setFilter("all")}
            >
              {t("inventory.transfer.all").text}
            </button>
            <button
              type="button"
              className={`transfer-filter-tab ${filter === "outbound" ? "transfer-filter-tab--active" : ""}`}
              onClick={() => setFilter("outbound")}
            >
              {t("inventory.transfer.outbound").text}
            </button>
            <button
              type="button"
              className={`transfer-filter-tab ${filter === "inbound" ? "transfer-filter-tab--active" : ""}`}
              onClick={() => setFilter("inbound")}
            >
              {t("inventory.transfer.inbound").text}
            </button>
          </div>
        )}

        {locationId !== "" && filteredTransfers.length === 0 && (
          <p className="transfer-empty-msg">{t("inventory.transfer.none").text}</p>
        )}

        <div className="transfer-list-items">
          {filteredTransfers.map((transfer: Transfer) => {
            const isSource = transfer.fromLocationId === locationId;
            const isDest = transfer.toLocationId === locationId;
            const isCancelling = cancellingTransferId === transfer.transferId;

            return (
              <article key={transfer.transferId} className="transfer-item-card">
                <div className="transfer-item-card__header">
                  <div className="transfer-item-route">
                    <span className="transfer-item-route__locations">
                      {`${nameOf(transfer.fromLocationId)} → ${nameOf(transfer.toLocationId)}`}
                    </span>
                    {transfer.documentNo ? (
                      <span className="transfer-item-docno">· {transfer.documentNo}</span>
                    ) : null}
                  </div>
                  <StateChip
                    state={transferChip(transfer.status)}
                    label={t(`inventory.transfer.status.${transfer.status}`).text}
                  />
                </div>
                {transfer.issuedAt && (
                  <div className="transfer-item-date">
                    <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                      <circle cx="12" cy="12" r="10" />
                      <polyline points="12 6 12 12 16 14" />
                    </svg>
                    <span>{formatInstant(transfer.issuedAt)}</span>
                  </div>
                )}
                {transfer.lines.length > 0 && (
                  <div className="modern-table-scroll transfer-table-wrapper">
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
                            <td className="numeric-cell" data-label={t("inventory.column.qty").text}>
                              {line.qty}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                <div className="transfer-item-actions">
                  {transfer.status === "ISSUED" && isSource && canIssue && (
                    <button
                      type="button"
                      className="table-action-btn"
                      disabled={dispatch.isPending}
                      onClick={() => dispatch.mutate(transfer.transferId)}
                    >
                      {t("inventory.transfer.dispatch").text}
                    </button>
                  )}
                  {transfer.status === "IN_TRANSIT" && isDest && canReceive && (
                    <button
                      type="button"
                      className="table-action-btn"
                      disabled={receive.isPending}
                      onClick={() => receive.mutate(transfer)}
                    >
                      {t("inventory.transfer.receive").text}
                    </button>
                  )}
                  {(transfer.status === "ISSUED" || transfer.status === "IN_TRANSIT") && isSource && canIssue && !isCancelling && (
                    <button
                      type="button"
                      className="table-action-btn table-action-btn--danger"
                      onClick={() => openCancel(transfer)}
                    >
                      {t("inventory.transfer.cancel").text}
                    </button>
                  )}
                </div>

                {isCancelling && (
                  <div className="transfer-cancel-panel">
                    <label className="transfer-cancel-field">
                      <span className="transfer-field-label">{t("inventory.transfer.cancel_reason").text}</span>
                      <input
                        type="text"
                        className="transfer-cancel-input"
                        maxLength={500}
                        value={cancelReason}
                        onChange={(e) => setCancelReason(e.target.value)}
                        placeholder={t("inventory.transfer.cancel_reason").text}
                      />
                    </label>
                    {transfer.status === "IN_TRANSIT" && (
                      <label className="transfer-verify-label">
                        <input
                          type="checkbox"
                          className="transfer-verify-checkbox"
                          checked={physicalReturnVerified}
                          onChange={(e) => setPhysicalReturnVerified(e.target.checked)}
                        />
                        <span>{t("inventory.transfer.physical_return_verified").text}</span>
                      </label>
                    )}
                    <div className="transfer-cancel-actions">
                      <button
                        type="button"
                        className="table-action-btn table-action-btn--danger"
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
                      <button type="button" className="table-action-btn" onClick={closeCancel}>
                        {t("inventory.transfer.cancel_close").text}
                      </button>
                    </div>
                  </div>
                )}
              </article>
            );
          })}
        </div>

        {dispatch.isError && (
          <p role="alert" className="transfer-error-alert">{errorText(dispatch.error, t("inventory.error.generic").text)}</p>
        )}
        {receive.isError && (
          <p role="alert" className="transfer-error-alert">{errorText(receive.error, t("inventory.error.generic").text)}</p>
        )}
        {cancel.isError && (
          <p role="alert" className="transfer-error-alert">{errorText(cancel.error, t("inventory.error.generic").text)}</p>
        )}
      </section>

      {canIssue && !locations.isError && (
        <section className="modern-table-card inventory-form-card" style={{ marginTop: 'var(--space-4)' }}>
          <div className="inventory-card-header">
            <span className="inventory-card-header__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <path d="M22 2L11 13" />
                <path d="M22 2L15 22L11 13L2 9L22 2Z" />
              </svg>
            </span>
            <h2 className="inventory-card-header__title">{t("inventory.transfer.send").text}</h2>
          </div>
          <div className="transfer-send-body">
            <label className="inventory-form-field transfer-destination-field">
              <span className="transfer-field-label">{t("inventory.transfer.destination").text}</span>
              <CustomSelect
                ariaLabel={t("inventory.transfer.destination").text}
                value={toLocationId}
                onChange={setToLocationId}
                options={[
                  { value: "", label: "" },
                  ...destinations.map((l) => ({
                    value: l.locationId,
                    label: locationText(l, locale)
                  }))
                ]}
              />
            </label>
            {sendable.length === 0 && <p className="transfer-empty-msg">{t("inventory.balances.empty").text}</p>}
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
                        <td className="numeric-cell">{lot.qtyOnHand}</td>
                        <td className="numeric-cell">
                          <input
                            type="text"
                            inputMode="decimal"
                            className="transfer-qty-input"
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
            <div className="inventory-action-bar">
              <button
                type="button"
                className="modern-btn modern-btn--primary inventory-submit-btn"
                disabled={issue.isPending || toLocationId === "" || lineCount === 0}
                onClick={() => issue.mutate()}
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                  <path d="M22 2L11 13" />
                  <path d="M22 2L15 22L11 13L2 9L22 2Z" />
                </svg>
                <span>{t("inventory.transfer.issue").text}</span>
              </button>
            </div>
            {issue.isError && <p role="alert">{errorText(issue.error, t("inventory.error.generic").text)}</p>}
          </div>
        </section>
      )}
    </main>
  );
}
