import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useInfiniteQuery } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useAdminApi } from "./adminApi";
import type { User, UserStatus } from "./adminApi";
import { USER_STATUSES, USER_STATUS_LOOK, refusalOf } from "./adminView";

/**
 * The users of the caller's entity (21A section 8, "Users"; M1-07). The server narrows the list
 * to the scope: an entity-wide administrator sees every user of the entity, a shop-scoped one
 * the users of that shop. A row leads to the user's card, where credentials and roles are
 * managed; "New user" is offered only to a user who may manage users.
 */
export function UsersPage() {
  const t = useT();
  const api = useAdminApi();
  const canManage = useHasPermission("gov.user.manage");
  const [status, setStatus] = useState<UserStatus | "">("");

  const users = useInfiniteQuery({
    queryKey: ["party", "users", status],
    queryFn: ({ pageParam }) => api.listUsers({ status: status || undefined }, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined
  });
  const shown: User[] = useMemo(() => users.data?.pages.flatMap((page) => page.items) ?? [], [users.data]);
  const empty = users.isSuccess && shown.length === 0 && !users.hasNextPage;

  return (
    <main className="shell-page">
      <PageHeader
        icon="society"
        title={t("party.users.title").text}
        actions={
          canManage ? (
            <Link className="modern-btn" to="/party/users/new">
              {t("party.users.new").text}
            </Link>
          ) : undefined
        }
      />

      <form role="search" onSubmit={(event) => event.preventDefault()} className="modern-filter-panel">
        <label className="modern-field">
          <span className="modern-field__label party-filter-label">{t("party.filter.status").text}</span>
          <div className="modern-select">
            <select value={status} onChange={(event) => setStatus(event.target.value as UserStatus | "")}>
              <option value="">{t("party.filter.status.any").text}</option>
              {USER_STATUSES.map((value) => (
                <option key={value} value={value}>
                  {t(`party.user.status.${value}`).text}
                </option>
              ))}
            </select>
          </div>
        </label>
      </form>

      {users.isLoading && <p>{t("party.list.loading").text}</p>}
      {users.isError && <p role="alert">{refusalOf(users.error, t("party.error.generic").text).text}</p>}
      {empty && <p>{t("party.users.empty").text}</p>}

      {shown.length > 0 && (
        <div className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th scope="col">{t("party.user.username").text}</th>
                  <th scope="col">{t("party.user.display_name").text}</th>
                  <th scope="col">{t("party.user.kind").text}</th>
                  <th scope="col">{t("party.field.language").text}</th>
                  <th scope="col">{t("party.col.status").text}</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((user) => (
                  <tr key={user.userId}>
                    <td>
                      <Link to={`/party/users/${user.userId}`}>{user.username}</Link>
                    </td>
                    <td>{user.displayName}</td>
                    <td>{t(`party.user.kind.${user.userKind}`).text}</td>
                    <td>{t(`party.language.${user.language}`).text}</td>
                    <td>
                      <StateChip state={USER_STATUS_LOOK[user.status]} label={t(`party.user.status.${user.status}`).text} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {users.hasNextPage && (
        <button type="button" className="modern-btn party-load-more" onClick={() => users.fetchNextPage()} disabled={users.isFetchingNextPage}>
          {t("party.list.more").text}
        </button>
      )}
    </main>
  );
}
