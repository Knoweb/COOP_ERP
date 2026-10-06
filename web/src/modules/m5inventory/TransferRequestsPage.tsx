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
import { useInventoryApi, type Location, type TransferRequest } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import "./inventory.css";
import { SkuLabel } from "./SkuLabel";
import { errorText, requestChip, requestLinesOf, sourceFor } from "./stockView";
import { PageHeader } from "../../shell/components/PageHeader";

/**
 * Transfer requests (doc 24 section 4.7; M4-10, demo scope). A shop (`shop.transfer.request`) asks
 * its society for more of what it sells; it sees no other location of the society, so it leaves
 * the source to the society. The society (`mpcs.transfer.approve`) approves from its stores, or
 * rejects with a reason; on approval M5 issues the transfer, which the shop then receives on the
 * Transfers screen. The request, the decision and the transfer are each written by their own side.
 */
export function TransferRequestsPage() {
  const t = useT();
  const { locale } = useIntl();
  const formatInstant = useFormatInstant();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const canRequest = useHasPermission("shop.transfer.request");
  const canDecide = useHasPermission("mpcs.transfer.approve");
  const requestKey = useIdempotencyKey();
  const decideKey = useIdempotencyKey();
  const [shopId, setShopId] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [reason, setReason] = useState("");
  const [sources, setSources] = useState<Record<string, string>>({});
  const [rejectReasons, setRejectReasons] = useState<Record<string, string>>({});

  const requests = useQuery({ queryKey: ["inventory", "transfer-requests"], queryFn: () => api.transferRequests() });
  const locations = useQuery({ queryKey: ["inventory", "locations"], queryFn: () => api.locations(), staleTime: Infinity });
  const shelf = useQuery({
    queryKey: ["inventory", "balances", shopId],
    queryFn: () => api.balances(shopId),
    enabled: canRequest && shopId !== ""
  });
  const items = [...new Set((shelf.data ?? []).map((lot) => lot.skuId))];

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["inventory"] });
  const forget = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };
  const ask = useMutation({
    mutationFn: () =>
      api.requestTransfer(
        {
          toLocationId: shopId,
          reason: reason.trim() === "" ? undefined : reason.trim(),
          lines: requestLinesOf(quantities)
        },
        requestKey.current()
      ),
    onSuccess: () => {
      requestKey.next();
      setQuantities({});
      setReason("");
      refresh();
    },
    onError: forget(requestKey)
  });
  const approve = useMutation({
    mutationFn: (request: TransferRequest) =>
      api.approveTransferRequest(
        request.requestId,
        sourceFor(request, sources, locations.data ?? []),
        decideKey.current()
      ),
    onSuccess: () => {
      decideKey.next();
      refresh();
    },
    onError: forget(decideKey)
  });
  const reject = useMutation({
    mutationFn: (requestId: string) =>
      api.rejectTransferRequest(requestId, (rejectReasons[requestId] ?? "").trim(), decideKey.current()),
    onSuccess: () => {
      decideKey.next();
      refresh();
    },
    onError: forget(decideKey)
  });

  const nameOf = (id: string | undefined) => {
    if (!id) {
      return t("inventory.request.source_open").text;
    }
    const found: Location | undefined = (locations.data ?? []).find((l) => l.locationId === id);
    return found ? locationText(found, locale) : t("inventory.request.stores").text;
  };

  return (
    <main className="shell-page">
      <PageHeader
        icon="stock"
        title={t("inventory.title").text}
        actions={
          <Link className="action-link" to="/inventory">
            <span>{t("inventory.back").text}</span>
          </Link>
        }
      />

      <div className="inventory-control-links">
        <Link className="action-link" to="/inventory/counts">
          <span>{t("inventory.counts.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/write-offs">
          <span>{t("inventory.writeoffs.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/repacks">
          <span>{t("inventory.repacks.link").text}</span>
        </Link>
        <Link className="action-link action-link--primary" to="/inventory/transfer-requests">
          <span>{t("inventory.request.link").text}</span>
        </Link>
      </div>

      {canRequest && (
        <section className="modern-table-card inventory-section" style={{ padding: 'var(--space-4)', marginBottom: 'var(--space-4)' }}>
          <h2>{t("inventory.request.new").text}</h2>
          <section className="modern-filter-panel modern-filter-panel--stock">
            <div className="modern-location-picker">
              <LocationPicker value={shopId} onChange={setShopId} />
            </div>
          </section>
          {shelf.data && items.length === 0 && <p>{t("inventory.balances.empty").text}</p>}
          {items.length > 0 && (
            <div className="modern-table-scroll" style={{ marginTop: 'var(--space-3)' }}>
              <table className="modern-table stock-table">
              <thead>
                <tr>
                  <th>{t("inventory.column.item").text}</th>
                  <th>{t("inventory.request.qty").text}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((skuId) => (
                  <tr key={skuId}>
                    <td>
                      <SkuLabel skuId={skuId} />
                    </td>
                    <td>
                      <input
                        inputMode="decimal"
                        aria-label={t("inventory.request.qty").text}
                        value={quantities[skuId] ?? ""}
                        onChange={(event) => setQuantities({ ...quantities, [skuId]: event.target.value })}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
              </table>
            </div>
          )}
          <label className="inventory-form-field">
            {t("inventory.request.reason").text}
            <input type="text" maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} />
          </label>
          <button
            type="button"
            disabled={ask.isPending || shopId === "" || requestLinesOf(quantities).length === 0}
            onClick={() => ask.mutate()}
          >
            {t("inventory.request.send").text}
          </button>
          {ask.isError && <p role="alert">{errorText(ask.error, t("inventory.error.generic").text)}</p>}
        </section>
      )}

      <section className="modern-table-card">
        <div style={{ padding: 'var(--space-4) var(--space-4) 0' }}>
          <h2>{t("inventory.request.list").text}</h2>
        </div>
      {requests.isLoading && <p style={{ padding: '0 var(--space-4)' }}>{t("inventory.loading").text}</p>}
      {requests.isError && <p role="alert" style={{ padding: '0 var(--space-4)' }}>{errorText(requests.error, t("inventory.error.generic").text)}</p>}
      {requests.data?.length === 0 && <p style={{ padding: '0 var(--space-4) var(--space-4)' }}>{t("inventory.request.none").text}</p>}
      {(requests.data ?? []).map((request) => (
        <section key={request.requestId} className="inventory-section" style={{ padding: '0 var(--space-4) var(--space-4)' }}>
          <p>
            {`${nameOf(request.fromLocationId)} → ${nameOf(request.toLocationId)} · ${formatInstant(request.requestedAt)} `}
            <StateChip
              state={requestChip(request.status)}
              label={t(`inventory.request.status.${request.status}`).text}
            />
          </p>
          {request.reason && <p>{request.reason}</p>}
          {request.rejectReason && <p>{request.rejectReason}</p>}
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
            <thead>
              <tr>
                <th>{t("inventory.column.item").text}</th>
                <th>{t("inventory.column.qty").text}</th>
              </tr>
            </thead>
            <tbody>
              {request.lines.map((line) => (
                <tr key={line.lineId}>
                  <td>
                    <SkuLabel skuId={line.skuId} />
                  </td>
                  <td>{line.qty}</td>
                </tr>
              ))}
            </tbody>
            </table>
          </div>
          {request.status === "APPROVED" && (
            <p>
              {request.transferStatus
                ? t(`inventory.transfer.status.${request.transferStatus}`).text
                : t("inventory.request.transfer_pending").text}{" "}
              <Link to="/inventory/transfers">{t("inventory.transfer.link").text}</Link>
            </p>
          )}
          {request.status === "REQUESTED" && canDecide && (
            <div className="inventory-control-links">
              <label className="inventory-form-field">
                {t("inventory.request.source").text}
                <select
                  aria-label={t("inventory.request.source").text}
                  value={sourceFor(request, sources, locations.data ?? []) ?? ""}
                  onChange={(event) => setSources({ ...sources, [request.requestId]: event.target.value })}
                >
                  <option value="" />
                  {(locations.data ?? [])
                    .filter((l) => l.locationId !== request.toLocationId)
                    .map((l) => (
                      <option key={l.locationId} value={l.locationId}>
                        {locationText(l, locale)}
                      </option>
                    ))}
                </select>
              </label>
              <button
                type="button"
                disabled={approve.isPending || !sourceFor(request, sources, locations.data ?? [])}
                onClick={() => approve.mutate(request)}
              >
                {t("inventory.request.approve").text}
              </button>
              <label className="inventory-form-field">
                {t("inventory.request.reject_reason").text}
                <input
                  type="text"
                  maxLength={500}
                  value={rejectReasons[request.requestId] ?? ""}
                  onChange={(event) => setRejectReasons({ ...rejectReasons, [request.requestId]: event.target.value })}
                />
              </label>
              <button
                type="button"
                disabled={reject.isPending || (rejectReasons[request.requestId] ?? "").trim() === ""}
                onClick={() => reject.mutate(request.requestId)}
              >
                {t("inventory.request.reject").text}
              </button>
            </div>
          )}
        </section>
      ))}
      </section>
      {[approve, reject].map((m, i) =>
        m.isError ? (
          <p key={i} role="alert">
            {errorText(m.error, t("inventory.error.generic").text)}
          </p>
        ) : null
      )}
    </main>
  );
}
