import { useEffect, useMemo, useState } from "react";
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
import { CustomSelect } from "./CustomSelect";
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
  const [searchQuery, setSearchQuery] = useState("");
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

  const summariesQuery = useQuery({
    queryKey: ["inventory", "stock-summaries", shopId],
    queryFn: () => (api.stockSummaries ? api.stockSummaries(shopId) : Promise.resolve([])),
    enabled: canRequest && shopId !== ""
  });

  const summaryMap = useMemo(() => {
    const map = new Map<string, { code: string; name: string }>();
    for (const s of summariesQuery.data ?? []) {
      const name = locale === "si" && s.skuNameSi ? s.skuNameSi : locale === "ta" && s.skuNameTa ? s.skuNameTa : s.skuNameEn;
      map.set(s.skuId, { code: s.skuCode, name });
    }
    return map;
  }, [summariesQuery.data, locale]);

  useEffect(() => {
    if (items.length > 0 && api.getSku) {
      for (const skuId of items) {
        if (!queryClient.getQueryData(["inventory", "sku", skuId])) {
          queryClient.prefetchQuery({
            queryKey: ["inventory", "sku", skuId],
            queryFn: () => api.getSku(skuId),
            staleTime: Infinity
          });
        }
      }
    }
  }, [items, api, queryClient]);

  const filteredItems = useMemo(() => {
    if (!searchQuery.trim()) return items;
    const q = searchQuery.toLowerCase().trim();
    return items.filter((skuId) => {
      if (skuId.toLowerCase().includes(q)) return true;
      const summary = summaryMap.get(skuId);
      if (summary) {
        if (summary.code?.toLowerCase().includes(q)) return true;
        if (summary.name?.toLowerCase().includes(q)) return true;
      }
      const cached = queryClient.getQueryData<any>(["inventory", "sku", skuId]);
      if (cached) {
        if (cached.skuCode?.toLowerCase().includes(q)) return true;
        if (cached.nameEn?.toLowerCase().includes(q)) return true;
        if (cached.nameSi?.toLowerCase().includes(q)) return true;
        if (cached.nameTa?.toLowerCase().includes(q)) return true;
      }
      return false;
    });
  }, [items, searchQuery, summaryMap, queryClient]);

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
      setSearchQuery("");
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
        title={t("inventory.request.title").text}
      />

      <div className="inventory-control-links">
        <Link className="action-link" to="/inventory/counts">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M9 11l3 3L22 4" />
            <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
          </svg>
          <span>{t("inventory.counts.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/write-offs">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="9" y1="15" x2="15" y2="15" />
          </svg>
          <span>{t("inventory.writeoffs.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/repacks">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="m21 16-4 4-4-4" />
            <path d="M17 20V4" />
            <path d="m3 8 4-4 4 4" />
            <path d="M7 4v16" />
          </svg>
          <span>{t("inventory.repacks.link").text}</span>
        </Link>
        <Link className="action-link action-link--primary" to="/inventory/transfer-requests">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <rect x="1" y="3" width="15" height="13" />
            <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
            <circle cx="5.5" cy="18.5" r="2.5" />
            <circle cx="18.5" cy="18.5" r="2.5" />
          </svg>
          <span>{t("inventory.request.link").text}</span>
        </Link>
      </div>

      {canRequest && (
        <section className="modern-table-card inventory-form-card" style={{ marginBottom: 'var(--space-4)' }}>
          <div className="inventory-card-header">
            <span className="inventory-card-header__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <rect x="1" y="3" width="15" height="13" />
                <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
                <circle cx="5.5" cy="18.5" r="2.5" />
                <circle cx="18.5" cy="18.5" r="2.5" />
              </svg>
            </span>
            <h2 className="inventory-card-header__title">{t("inventory.request.new").text}</h2>
          </div>
          <div style={{ padding: 'var(--space-4)' }}>
            <section className="modern-filter-panel modern-filter-panel--stock">
              <div className="modern-location-picker">
                <LocationPicker value={shopId} onChange={setShopId} />
              </div>
            </section>
            {shelf.data && items.length === 0 && <p style={{ padding: 'var(--space-2) 0' }}>{t("inventory.balances.empty").text}</p>}
            {items.length > 0 && (
              <>
                <div className="transfer-request-toolbar">
                  <div className="stock-search-wrap">
                    <span className="modern-control modern-control--search stock-search-control">
                      <svg
                        className="modern-control__leading-icon"
                        viewBox="0 0 24 24"
                        aria-hidden="true"
                        focusable="false"
                      >
                        <circle cx="11" cy="11" r="6" />
                        <path d="m16 16 4 4" />
                      </svg>
                      <input
                        type="search"
                        value={searchQuery}
                        onChange={(event) => setSearchQuery(event.target.value)}
                        placeholder={t("inventory.stock.search_placeholder").text}
                        aria-label={t("inventory.stock.search_placeholder").text}
                        className="stock-search-input"
                      />
                      {searchQuery ? (
                        <button
                          type="button"
                          className="stock-search-clear"
                          onClick={() => setSearchQuery("")}
                          aria-label={t("inventory.remove").text}
                        >
                          ✕
                        </button>
                      ) : null}
                    </span>
                  </div>
                </div>

                <div className="modern-table-scroll" style={{ marginTop: 'var(--space-2)' }}>
                  <table className="modern-table stock-table transfer-request-table">
                    <colgroup>
                      <col className="transfer-col-item" />
                      <col className="transfer-col-qty" />
                    </colgroup>
                    <thead>
                      <tr>
                        <th className="transfer-col-item">{t("inventory.column.item").text}</th>
                        <th className="transfer-col-qty">{t("inventory.request.qty").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredItems.length === 0 ? (
                        <tr>
                          <td colSpan={2} className="empty-table-cell">
                            {t("inventory.find.none").text}
                          </td>
                        </tr>
                      ) : (
                        filteredItems.map((skuId) => (
                          <tr key={skuId}>
                            <td className="transfer-col-item">
                              <SkuLabel skuId={skuId} />
                            </td>
                            <td className="transfer-col-qty">
                              <input
                                type="text"
                                inputMode="decimal"
                                className="transfer-qty-input"
                                aria-label={t("inventory.request.qty").text}
                                value={quantities[skuId] ?? ""}
                                onChange={(event) => setQuantities({ ...quantities, [skuId]: event.target.value })}
                              />
                            </td>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              </>
            )}
            <label className="inventory-form-field" style={{ marginTop: 'var(--space-3)' }}>
              {t("inventory.request.reason").text}
              <input type="text" maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} />
            </label>
            <div className="inventory-action-bar">
              <button
                type="button"
                className="inventory-submit-btn"
                disabled={ask.isPending || shopId === "" || requestLinesOf(quantities).length === 0}
                onClick={() => ask.mutate()}
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                  <line x1="22" y1="2" x2="11" y2="13" />
                  <polygon points="22 2 15 22 11 13 2 9 22 2" fill="currentColor" />
                </svg>
                <span>{t("inventory.request.send").text}</span>
              </button>
            </div>
            {ask.isError && <p role="alert">{errorText(ask.error, t("inventory.error.generic").text)}</p>}
          </div>
        </section>
      )}

      <section className="modern-table-card">
        <div className="inventory-card-header">
          <span className="inventory-card-header__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
              <rect x="1" y="3" width="15" height="13" />
              <polygon points="16 8 20 8 23 11 23 16 16 16 8" />
              <circle cx="5.5" cy="18.5" r="2.5" />
              <circle cx="18.5" cy="18.5" r="2.5" />
            </svg>
          </span>
          <h2 className="inventory-card-header__title">{t("inventory.request.list").text}</h2>
        </div>
        {requests.isLoading && <p style={{ padding: 'var(--space-3) var(--space-4)' }}>{t("inventory.loading").text}</p>}
        {requests.isError && <p role="alert" style={{ padding: 'var(--space-3) var(--space-4)' }}>{errorText(requests.error, t("inventory.error.generic").text)}</p>}
        {requests.data?.length === 0 && <p style={{ padding: 'var(--space-3) var(--space-4)' }}>{t("inventory.request.none").text}</p>}
        {(requests.data ?? []).map((request) => (
          <section key={request.requestId} className="inventory-section" style={{ padding: 'var(--space-3) var(--space-4)' }}>
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
              <p style={{ marginTop: 'var(--space-2)' }}>
                {request.transferStatus
                  ? t(`inventory.transfer.status.${request.transferStatus}`).text
                  : t("inventory.request.transfer_pending").text}{" "}
                <Link to="/inventory/transfers">{t("inventory.transfer.link").text}</Link>
              </p>
            )}
            {request.status === "REQUESTED" && canDecide && (
              <div className="inventory-control-links" style={{ marginTop: 'var(--space-3)' }}>
                <label className="inventory-form-field">
                  <span>{t("inventory.request.source").text}</span>
                  <CustomSelect
                    ariaLabel={t("inventory.request.source").text}
                    value={sourceFor(request, sources, locations.data ?? []) ?? ""}
                    onChange={(val) => setSources({ ...sources, [request.requestId]: val })}
                    options={[
                      { value: "", label: "" },
                      ...(locations.data ?? [])
                        .filter((l) => l.locationId !== request.toLocationId)
                        .map((l) => ({
                          value: l.locationId,
                          label: locationText(l, locale)
                        }))
                    ]}
                  />
                </label>
                <button
                  type="button"
                  className="modern-btn modern-btn--primary table-action-btn"
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
                  className="table-action-btn table-action-btn--danger"
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
