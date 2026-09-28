import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { PageHeader } from "../../shell/components/PageHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePosApi } from "./posApi";
import { ShopPicker, useShop } from "./ShopPicker";
import { TenderKinds, TillLabel } from "./labels";
import { errorText, receiptLook, tenderKinds, tillNumber } from "./posView";
import "./pos.css";

/**
 * The receipts the tills of a shop issued (26A section 8, demo scope), newest first: number,
 * time, till, total and how it was paid. Read-only: a sale is made at the till and reaches
 * central through the sync contract; this screen shows what arrived.
 */
export function ReceiptsPage() {
  const t = useT();
  const formatInstant = useFormatInstant();
  const api = usePosApi();
  const [locationId, setLocationId] = useShop();

  const receipts = useQuery({
    queryKey: ["pos", "receipts", locationId],
    queryFn: () => api.receipts(locationId),
    enabled: locationId !== ""
  });
  const positions = useQuery({
    queryKey: ["pos", "positions", locationId],
    queryFn: () => api.positions(locationId),
    enabled: locationId !== "",
    retry: false
  });

  return (
    <main className="shell-page">
      <PageHeader
        icon="stock"
        title={t("pos.receipts.title").text}
        actions={
          locationId ? (
            <Link className="action-link" to={`/pos/sessions?location=${locationId}`}>
              <span>{t("pos.sessions.link").text}</span>
            </Link>
          ) : null
        }
      />

      <section className="modern-filter-panel">
        <ShopPicker value={locationId} onChange={setLocationId} />
      </section>

      {receipts.isLoading && <p>{t("pos.loading").text}</p>}
      {receipts.isError && <p role="alert">{errorText(receipts.error, t("pos.error.generic").text)}</p>}
      {receipts.data?.length === 0 && <p>{t("pos.receipts.empty").text}</p>}

      {receipts.data && receipts.data.length > 0 && (
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
                {receipts.data.map((receipt) => (
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
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </main>
  );
}
