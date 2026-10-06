import { useMemo } from "react";
import { Link } from "react-router-dom";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { PageHeader } from "../../shell/components/PageHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePosApi, type SessionFilter, type TillSession } from "./posApi";
import { DayPicker, ShopPicker, useBusinessDay, useShop } from "./ShopPicker";
import { TillLabel } from "./labels";
import { errorText, tillNumber } from "./posView";
import "./pos.css";

/**
 * The till sessions of a shop on one business day, newest first, a page at a time (26A section
 * 8): when each opened, its float, and at the close what the cashier counted against what the
 * till expected. A close whose open never reached central is listed too, its open empty (wave 2,
 * M6-10), so the counted cash and the variance are never out of sight.
 */
export function SessionsPage() {
  const t = useT();
  const formatInstant = useFormatInstant();
  const formatDate = useFormatDate();
  const api = usePosApi();
  const [locationId, setLocationId] = useShop();
  const [day, setDay] = useBusinessDay();

  const filter: SessionFilter = { locationId, businessDate: day };
  const sessions = useInfiniteQuery({
    queryKey: ["pos", "sessions", filter],
    queryFn: ({ pageParam }) => api.sessions(filter, pageParam),
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

  const shown: TillSession[] = useMemo(() => sessions.data?.pages.flatMap((page) => page.items) ?? [], [sessions.data]);
  const empty = sessions.isSuccess && shown.length === 0 && !sessions.hasNextPage;

  return (
    <main className="shell-page">
      <Link className="back-link" to={`/pos?location=${locationId}&day=${day}`}>
        {t("pos.back").text}
      </Link>
      <PageHeader icon="stock" title={t("pos.sessions.title").text} />

      <section className="modern-filter-panel pos-filters">
        <ShopPicker value={locationId} onChange={setLocationId} />
        <DayPicker value={day} onChange={setDay} />
      </section>

      {sessions.isLoading && <p>{t("pos.loading").text}</p>}
      {sessions.isError && <p role="alert">{errorText(sessions.error, t("pos.error.generic").text)}</p>}
      {empty && <p>{t("pos.sessions.empty").text}</p>}

      {shown.length > 0 && (
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
                {shown.map((session) => (
                  <tr key={session.sessionId}>
                    <td>{session.businessDate ? formatDate(session.businessDate) : ""}</td>
                    <td>
                      <TillLabel number={tillNumber(positions.data, session.tillPositionId)} />
                    </td>
                    <td>{session.openedAt ? formatInstant(session.openedAt) : t("pos.session.open_missing").text}</td>
                    <td>{session.closedAt ? formatInstant(session.closedAt) : ""}</td>
                    <td className="numeric-cell">{session.openedAt && <MoneyDisplay amount={session.floatAmount} />}</td>
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

      {sessions.hasNextPage && (
        <button
          type="button"
          className="modern-btn pos-more"
          onClick={() => sessions.fetchNextPage()}
          disabled={sessions.isFetchingNextPage}
        >
          {t("pos.more").text}
        </button>
      )}
    </main>
  );
}
