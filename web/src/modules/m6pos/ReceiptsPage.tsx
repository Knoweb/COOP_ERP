import { useMemo } from "react";
import { Link } from "react-router-dom";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { PageHeader } from "../../shell/components/PageHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePosApi, type Receipt, type ReceiptFilter } from "./posApi";
import { DayPicker, ShopPicker, useBusinessDay, useFlaggedOnly, useShop } from "./ShopPicker";
import { FlagList, TenderKinds, TillLabel } from "./labels";
import { errorText, receiptLook, tenderKinds, tillNumber } from "./posView";
import "./pos.css";

/**
 * The receipts the tills of a shop issued on one business day (26A section 8), newest first, a
 * page at a time: number, time, till, total, how it was paid, and what central flagged, in the
 * reader's language. "Flagged only" is how a shop manager finds what central questioned (wave 2,
 * M6-08 and M6-09). Read-only: a sale is made at the till and reaches central through the sync
 * contract; this screen shows what arrived.
 */
export function ReceiptsPage() {
  const t = useT();
  const formatInstant = useFormatInstant();
  const api = usePosApi();
  const [locationId, setLocationId] = useShop();
  const [day, setDay] = useBusinessDay();
  const [flaggedOnly, setFlaggedOnly] = useFlaggedOnly();

  const filter: ReceiptFilter = { locationId, businessDate: day, flaggedOnly };
  const receipts = useInfiniteQuery({
    queryKey: ["pos", "receipts", filter],
    queryFn: ({ pageParam }) => api.receipts(filter, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: locationId !== ""
  });
  const positions = useQuery({
    queryKey: ["pos", "positions", locationId],
    queryFn: () => api.positions(locationId),
    enabled: locationId !== "",
    retry: false
  });

  const shown: Receipt[] = useMemo(() => receipts.data?.pages.flatMap((page) => page.items) ?? [], [receipts.data]);
  const empty = receipts.isSuccess && shown.length === 0 && !receipts.hasNextPage;

  return (
    <main className="shell-page">
      <PageHeader
        icon="stock"
        title={t("pos.receipts.title").text}
        actions={
          locationId ? (
            <Link className="action-link" to={`/pos/sessions?location=${locationId}&day=${day}`}>
              <span>{t("pos.sessions.link").text}</span>
            </Link>
          ) : null
        }
      />

      <section className="modern-filter-panel pos-filters">
        <ShopPicker value={locationId} onChange={setLocationId} />
        <DayPicker value={day} onChange={setDay} />
        <label className="pos-check">
          <input type="checkbox" checked={flaggedOnly} onChange={(event) => setFlaggedOnly(event.target.checked)} />
          {t("pos.field.flagged_only").text}
        </label>
      </section>

      {receipts.isLoading && <p>{t("pos.loading").text}</p>}
      {receipts.isError && <p role="alert">{errorText(receipts.error, t("pos.error.generic").text)}</p>}
      {empty && <p>{t(flaggedOnly ? "pos.receipts.empty_flagged" : "pos.receipts.empty").text}</p>}

      {shown.length > 0 && (
        <section className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th>{t("pos.column.number").text}</th>
                  <th>{t("pos.column.time").text}</th>
                  <th>{t("pos.column.till").text}</th>
                  <th>{t("pos.column.tender").text}</th>
                  <th className="numeric-cell">{t("pos.column.total").text}</th>
                  <th>{t("pos.column.state").text}</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((receipt) => (
                  <tr key={receipt.documentId}>
                    <td>
                      <Link
                        className="entity-link"
                        to={`/pos/receipts/${receipt.documentId}?location=${locationId}`}
                      >
                        {receipt.docNumberDisplay ?? t("pos.receipt.unnumbered").text}
                      </Link>
                    </td>
                    <td>{formatInstant(receipt.issuedAt)}</td>
                    <td>
                      <TillLabel number={tillNumber(positions.data, receipt.tillPositionId)} />
                    </td>
                    <td>
                      <TenderKinds kinds={tenderKinds(receipt)} />
                    </td>
                    <td className="numeric-cell">
                      <MoneyDisplay amount={receipt.grossAmount} />
                    </td>
                    <td>
                      <StateChip
                        state={receiptLook(receipt)}
                        label={t(receiptLook(receipt) === "alert" ? "pos.receipt.flagged" : "pos.receipt.issued").text}
                      />
                      {receipt.flags.length > 0 && <FlagList flags={receipt.flags} />}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {receipts.hasNextPage && (
        <button
          type="button"
          className="modern-btn pos-more"
          onClick={() => receipts.fetchNextPage()}
          disabled={receipts.isFetchingNextPage}
        >
          {t("pos.more").text}
        </button>
      )}
    </main>
  );
}
