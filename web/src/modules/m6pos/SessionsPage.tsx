import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { PageHeader } from "../../shell/components/PageHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePosApi } from "./posApi";
import { ShopPicker, useShop } from "./ShopPicker";
import { TillLabel } from "./labels";
import { errorText, tillNumber } from "./posView";
import "./pos.css";

/**
 * The till sessions of a shop, newest first (26A section 8, demo scope): when each opened, its
 * float, and at the close what the cashier counted against what the till expected.
 */
export function SessionsPage() {
  const t = useT();
  const formatInstant = useFormatInstant();
  const formatDate = useFormatDate();
  const api = usePosApi();
  const [locationId, setLocationId] = useShop();

  const sessions = useQuery({
    queryKey: ["pos", "sessions", locationId],
    queryFn: () => api.sessions(locationId),
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
      <Link className="back-link" to={`/pos?location=${locationId}`}>
        {t("pos.back").text}
      </Link>
      <PageHeader icon="stock" title={t("pos.sessions.title").text} />

      <section className="modern-filter-panel">
        <ShopPicker value={locationId} onChange={setLocationId} />
      </section>

      {sessions.isLoading && <p>{t("pos.loading").text}</p>}
      {sessions.isError && <p role="alert">{errorText(sessions.error, t("pos.error.generic").text)}</p>}
      {sessions.data?.length === 0 && <p>{t("pos.sessions.empty").text}</p>}

      {sessions.data && sessions.data.length > 0 && (
        <section className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th>{t("pos.field.business_date").text}</th>
                  <th>{t("pos.column.till").text}</th>
                  <th>{t("pos.column.opened").text}</th>
                  <th>{t("pos.column.closed").text}</th>
                  <th className="numeric-cell">{t("pos.column.float").text}</th>
                  <th className="numeric-cell">{t("pos.column.counted").text}</th>
                  <th className="numeric-cell">{t("pos.column.expected").text}</th>
                  <th className="numeric-cell">{t("pos.column.variance").text}</th>
                  <th>{t("pos.column.state").text}</th>
                </tr>
              </thead>
              <tbody>
                {sessions.data.map((session) => (
                  <tr key={session.sessionId}>
                    <td>{session.businessDate ? formatDate(session.businessDate) : ""}</td>
                    <td>
                      <TillLabel number={tillNumber(positions.data, session.tillPositionId)} />
                    </td>
                    <td>{formatInstant(session.openedAt)}</td>
                    <td>{session.closedAt ? formatInstant(session.closedAt) : ""}</td>
                    <td className="numeric-cell"><MoneyDisplay amount={session.floatAmount} /></td>
                    <td className="numeric-cell">{session.closedAt && <MoneyDisplay amount={session.countedCash} />}</td>
                    <td className="numeric-cell">{session.closedAt && <MoneyDisplay amount={session.expectedCash} />}</td>
                    <td className="numeric-cell">{session.closedAt && <MoneyDisplay amount={session.variance} />}</td>
                    <td>
                      <StateChip
                        state={session.status === "OPEN" ? "draft" : "issued"}
                        label={t(`pos.session.status.${session.status}`).text}
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
