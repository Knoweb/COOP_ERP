import { useMemo, useState } from "react";
import { useIntl } from "react-intl";
import { Link } from "react-router-dom";
import { useInfiniteQuery } from "@tanstack/react-query";
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
 * governs, as a list a clerk can narrow. Two kinds of narrowing, on purpose:
 *   - status and district go to the SERVER, which filters and pages (the register will hold
 *     hundreds of societies, and the district is an index there);
 *   - the search box filters what has been LOADED, by code or name, as a person types; it never
 *     calls the server, so it is instant and costs nothing.
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

  const filter = { status: status || undefined, district: district.trim() || undefined };

  const societies = useInfiniteQuery({
    queryKey: ["party", "societies", filter],
    queryFn: ({ pageParam }) => api.listSocieties(filter, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined
  });

  const loaded: Society[] = useMemo(() => societies.data?.pages.flatMap((page) => page.items) ?? [], [societies.data]);

  const shown = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) {
      return loaded;
    }
    return loaded.filter((society) =>
      [society.entityCode, society.legalNameEn, society.legalNameSi, society.legalNameTa]
        .filter((value): value is string => Boolean(value))
        .some((value) => value.toLowerCase().includes(needle))
    );
  }, [loaded, search]);

  return (
    <main className="shell-page">
      <h1>{t("party.register.title").text}</h1>

      {canRegister && (
        <nav aria-label={t("party.register.actions").text} style={{ display: "flex", gap: "var(--target-gap)", marginBottom: "var(--space-3)" }}>
          <Link to="/party/societies/new">{t("party.register.new").text}</Link>
          <Link to="/party/societies/bulk">{t("party.register.bulk").text}</Link>
        </nav>
      )}

      <form role="search" onSubmit={(event) => event.preventDefault()} style={{ display: "grid", gap: "var(--target-gap)", marginBottom: "var(--space-3)" }}>
        <label style={field}>
          {t("party.filter.search").text}
          <input type="search" value={search} onChange={(event) => setSearch(event.target.value)} />
        </label>
        <label style={field}>
          {t("party.filter.status").text}
          <select value={status} onChange={(event) => setStatus(event.target.value as SocietyStatus | "")}>
            <option value="">{t("party.filter.status.any").text}</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {t(statusMessageId(value)).text}
              </option>
            ))}
          </select>
        </label>
        <label style={field}>
          {t("party.filter.district").text}
          <input type="text" value={district} onChange={(event) => setDistrict(event.target.value)} />
        </label>
      </form>

      {societies.isLoading && <p>{t("party.list.loading").text}</p>}
      {societies.isError && <p role="alert">{errorText(societies.error, t("party.error.generic").text)}</p>}
      {societies.isSuccess && shown.length === 0 && <p>{t("party.list.empty").text}</p>}

      {shown.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr>
              <th scope="col" style={cell}>{t("party.col.code").text}</th>
              <th scope="col" style={cell}>{t("party.col.name").text}</th>
              <th scope="col" style={cell}>{t("party.col.district").text}</th>
              <th scope="col" style={cell}>{t("party.col.status").text}</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((society) => (
              <tr key={society.entityId}>
                <td style={cell}>
                  <Link to={`/party/societies/${society.entityId}`}>{society.entityCode}</Link>
                </td>
                <td style={cell}>
                  <SocietyName society={society} locale={locale} />
                </td>
                <td style={cell}>{society.district ?? ""}</td>
                <td style={cell}>
                  {society.status && <StateChip state={STATUS_LOOK[society.status]} label={t(statusMessageId(society.status)).text} />}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {societies.hasNextPage && (
        <button type="button" onClick={() => societies.fetchNextPage()} disabled={societies.isFetchingNextPage} style={{ marginTop: "var(--space-2)" }}>
          {t("party.list.more").text}
        </button>
      )}
    </main>
  );
}

const field = { display: "grid", gap: "var(--space-half)" };
const cell = { textAlign: "left" as const, padding: "var(--space-1)", borderBottom: "var(--border-width) solid var(--color-border)" };

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
