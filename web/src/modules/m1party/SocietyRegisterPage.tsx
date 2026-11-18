import { useDeferredValue, useEffect, useMemo, useRef, useState } from "react";
import { useIntl } from "react-intl";
import { Link } from "react-router-dom";
import { useInfiniteQuery } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { LangFallbackTag } from "../../shell/i18n/LangFallbackTag";
import { ApiProblem } from "../../shell/api/client";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { usePartyApi } from "./partyApi";
import type { Society, SocietyStatus } from "./partyApi";
import { STATUSES, STATUS_LOOK, legalNameIn, statusMessageId } from "./societyView";

const DISTRICTS = [
  "Ampara",
  "Anuradhapura",
  "Badulla",
  "Batticaloa",
  "Colombo",
  "Galle",
  "Gampaha",
  "Hambantota",
  "Jaffna",
  "Kalutara",
  "Kandy",
  "Kegalle",
  "Kilinochchi",
  "Kurunegala",
  "Mannar",
  "Matale",
  "Matara",
  "Monaragala",
  "Mullaitivu",
  "Nuwara Eliya",
  "Polonnaruwa",
  "Puttalam",
  "Ratnapura",
  "Trincomalee",
  "Vavuniya"
];

/** Custom Status dropdown with styled selection, checkmark, custom hover and responsive layout */
function StatusDropdown(props: {
  value: SocietyStatus | "";
  onChange: (value: SocietyStatus | "") => void;
  anyLabel: string;
  ariaLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const t = useT();

  const options: { value: SocietyStatus | ""; label: string }[] = [
    { value: "", label: props.anyLabel },
    ...STATUSES.map((status) => ({
      value: status,
      label: t(statusMessageId(status)).text
    }))
  ];

  const currentOption = options.find((opt) => opt.value === props.value) ?? options[0];

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("touchstart", handleClickOutside);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  return (
    <div className="custom-status-dropdown" ref={containerRef}>
      <button
        type="button"
        className="custom-status-dropdown__trigger"
        aria-label={props.ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className="custom-status-dropdown__label">{currentOption.label}</span>
        <svg
          className="custom-status-dropdown__arrow"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="m7 9 5 5 5-5" />
        </svg>
      </button>

      {open && (
        <ul className="custom-status-dropdown__menu" role="listbox">
          {options.map((opt) => {
            const isSelected = props.value === opt.value;
            return (
              <li key={opt.value} role="presentation">
                <button
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  className={
                    isSelected
                      ? "custom-status-dropdown__option custom-status-dropdown__option--selected"
                      : "custom-status-dropdown__option"
                  }
                  onClick={() => {
                    props.onChange(opt.value);
                    setOpen(false);
                  }}
                >
                  <span className="custom-status-dropdown__option-label">{opt.label}</span>
                  {isSelected && (
                    <span className="custom-status-dropdown__check" aria-hidden="true">
                      <svg
                        viewBox="0 0 24 24"
                        width="16"
                        height="16"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      >
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** Custom District dropdown with styled selection, checkmark, custom hover and scrollable list */
function DistrictDropdown(props: {
  value: string;
  onChange: (value: string) => void;
  anyLabel: string;
  ariaLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLUListElement>(null);

  const options: { value: string; label: string }[] = [
    { value: "", label: props.anyLabel },
    ...DISTRICTS.map((name) => ({
      value: name,
      label: name
    }))
  ];

  const currentOption = options.find((opt) => opt.value === props.value) ?? options[0];

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("touchstart", handleClickOutside);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (open && menuRef.current) {
      const selected = menuRef.current.querySelector<HTMLElement>(".custom-status-dropdown__option--selected");
      if (selected) {
        selected.scrollIntoView({ block: "nearest" });
      }
    }
  }, [open]);

  return (
    <div className="custom-status-dropdown" ref={containerRef}>
      <button
        type="button"
        className="custom-status-dropdown__trigger"
        aria-label={props.ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className="custom-status-dropdown__label">{currentOption.label}</span>
        <svg
          className="custom-status-dropdown__arrow"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="m7 9 5 5 5-5" />
        </svg>
      </button>

      {open && (
        <ul ref={menuRef} className="custom-status-dropdown__menu custom-status-dropdown__menu--scrollable" role="listbox">
          {options.map((opt) => {
            const isSelected = props.value === opt.value;
            return (
              <li key={opt.value} role="presentation">
                <button
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  className={
                    isSelected
                      ? "custom-status-dropdown__option custom-status-dropdown__option--selected"
                      : "custom-status-dropdown__option"
                  }
                  onClick={() => {
                    props.onChange(opt.value);
                    setOpen(false);
                  }}
                >
                  <span className="custom-status-dropdown__option-label">{opt.label}</span>
                  {isSelected && (
                    <span className="custom-status-dropdown__check" aria-hidden="true">
                      <svg
                        viewBox="0 0 24 24"
                        width="16"
                        height="16"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      >
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

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
      <PageHeader
        icon="society"
        title={t("party.register.title").text}
        actions={
          canRegister ? (
            <div aria-label={t("party.register.actions").text} style={{ display: "contents" }}>
              <Link className="modern-btn" to="/party/societies/new">{t("party.register.new").text}</Link>
              <Link className="modern-btn" to="/party/societies/bulk">{t("party.register.bulk").text}</Link>
            </div>
          ) : undefined
        }
      />

      <div className="society-filters-wrap">
        <form id="society-filters" role="search" onSubmit={(event) => event.preventDefault()} className="society-filter-panel">
          <label className="modern-field">
            <span className="modern-field__label party-filter-label">{t("party.filter.search").text}</span>
            <input type="search" value={search} onChange={(event) => setSearch(event.target.value)} maxLength={200} className="party-filter-input" />
          </label>
          <div className="modern-field">
            <span className="modern-field__label party-filter-label">{t("party.filter.status").text}</span>
            <StatusDropdown
              value={status}
              onChange={(val) => setStatus(val)}
              anyLabel={t("party.filter.status.any").text}
              ariaLabel={t("party.filter.status").text}
            />
          </div>
          <div className="modern-field">
            <span className="modern-field__label party-filter-label">
              {t("party.filter.district").text}
            </span>
            <DistrictDropdown
              value={district}
              onChange={(val) => setDistrict(val)}
              anyLabel={t("party.filter.district.any").text}
              ariaLabel={t("party.filter.district").text}
            />
          </div>
        </form>
      </div>

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
