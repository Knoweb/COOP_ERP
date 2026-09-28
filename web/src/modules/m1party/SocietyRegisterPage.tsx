import { useDeferredValue, useMemo, useState } from "react";
import { useIntl } from "react-intl";
import { Link } from "react-router-dom";
import { useInfiniteQuery } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { LangFallbackTag } from "../../shell/i18n/LangFallbackTag";
import { ApiProblem } from "../../shell/api/client";
import { useHasPermission } from "../../shell/auth/permissions";
import { StateChip } from "../../shell/components/StateChip";
import { usePartyApi } from "./partyApi";
import type { Society, SocietyStatus } from "./partyApi";
import { STATUSES, STATUS_LOOK, legalNameIn, statusMessageId } from "./societyView";

/**
 * The society register (21A section 8, "Society register"): every entity the Federation
 * governs, as a list a clerk can narrow. Status, district and the search box all go to the
 * SERVER, which filters and pages: the register will hold hundreds of societies, a hundred to
 * a page, and a society on a later page must be found as surely as one on the first. (The
 * search box used to filter only the pages already loaded, so a society past the first page
 * was reported as "no societies match"; the review of 26 September.) The search text is sent
 * deferred, so that a person typing fast does not fire a request per keystroke.
 * A row leads to the society's card; the links above the list lead to the register form and
 * the bulk upload, and are not offered to a user without the permission to register.
 */
export function SocietyRegisterPage() {
  const t = useT();
  const { locale } = useIntl();
  const api = usePartyApi();
  const canRegister = useHasPermission("gov.entity.register");

  const [status, setStatus] = useState<SocietyStatus | "">("");
  const [district, setDistrict] = useState("");
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);

  const filter = {
    status: status || undefined,
    district: district.trim() || undefined,
    query: deferredSearch.trim() || undefined
  };

  const societies = useInfiniteQuery({
    queryKey: ["party", "societies", filter],
    queryFn: ({ pageParam }) => api.listSocieties(filter, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined
  });

  const shown: Society[] = useMemo(() => societies.data?.pages.flatMap((page) => page.items) ?? [], [societies.data]);

  // "No societies match" is said only when the server has no more pages: a page can be empty
  // while a later one is not, and a clerk must never be told the register is empty by mistake.
  const empty = societies.isSuccess && shown.length === 0 && !societies.hasNextPage;

  return (
    <main className="shell-page">
      <div className="page-heading">
        <div className="page-heading__icon">
          <svg viewBox="0 0 24 24" width="24" height="24" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M23 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path></svg>
        </div>
        <div className="page-heading__copy">
          <h1>{t("party.register.title").text}</h1>
        </div>
        {canRegister && (
          <div className="page-heading__actions" aria-label={t("party.register.actions").text}>
            <Link className="modern-btn" to="/party/societies/new">{t("party.register.new").text}</Link>
            <Link className="modern-btn" to="/party/societies/bulk">{t("party.register.bulk").text}</Link>
          </div>
        )}
      </div>

      <form role="search" onSubmit={(event) => event.preventDefault()} className="modern-filter-panel">
        <label className="modern-field">
          <span className="modern-field__label party-filter-label">{t("party.filter.search").text}</span>
          <input type="search" value={search} onChange={(event) => setSearch(event.target.value)} maxLength={200} className="party-filter-input" />
        </label>
        <label className="modern-field">
          <span className="modern-field__label party-filter-label">{t("party.filter.status").text}</span>
          <div className="modern-select">
            <select value={status} onChange={(event) => setStatus(event.target.value as SocietyStatus | "")}>
              <option value="">{t("party.filter.status.any").text}</option>
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {t(statusMessageId(value)).text}
                </option>
              ))}
            </select>
            <svg className="modern-select__arrow" viewBox="0 0 24 24"><path d="m7 9 5 5 5-5" /></svg>
          </div>
        </label>
        <label className="modern-field">
          <span className="modern-field__label party-filter-label">{t("party.filter.district").text}</span>
          <input type="text" value={district} onChange={(event) => setDistrict(event.target.value)} className="party-filter-input" />
        </label>
      </form>

      {societies.isLoading && <p>{t("party.list.loading").text}</p>}
      {societies.isError && <p role="alert">{errorText(societies.error, t("party.error.generic").text)}</p>}
      {empty && <p>{t("party.list.empty").text}</p>}

      {shown.length > 0 && (
        <div className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th scope="col">{t("party.col.code").text}</th>
                  <th scope="col">{t("party.col.name").text}</th>
                  <th scope="col">{t("party.col.district").text}</th>
                  <th scope="col">{t("party.col.status").text}</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((society) => (
                  <tr key={society.entityId}>
                    <td>
                      <Link to={`/party/societies/${society.entityId}`}>{society.entityCode}</Link>
                    </td>
                    <td>
                      <SocietyName society={society} locale={locale} />
                    </td>
                    <td>{society.district ?? ""}</td>
                    <td>
                      {society.status && <StateChip state={STATUS_LOOK[society.status]} label={t(statusMessageId(society.status)).text} />}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {societies.hasNextPage && (
        <button type="button" className="modern-btn party-load-more" onClick={() => societies.fetchNextPage()} disabled={societies.isFetchingNextPage}>
          {t("party.list.more").text}
        </button>
      )}
    </main>
  );
}
/** The legal name in the user's language, or in English with the EN tag when not translated. */
export function SocietyName({ society, locale }: { society: Society; locale: string }) {
  const translated = legalNameIn(society, locale);
  if (translated) {
    return <span lang={locale}>{translated}</span>;
  }
  return (
    <span lang="en">
      {society.legalNameEn}
      <LangFallbackTag />
    </span>
  );
}

/** The title of a problem document (already in the user's language), or the screen's own fallback. */
export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}
