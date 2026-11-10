import { useMemo, useState } from "react";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useAdminApi } from "./adminApi";
import { refusalOf, roleName } from "./adminView";
import { CustomSelect } from "./CustomSelect";
import { getPermissionInfo, getRoleDuties } from "./permissionMeta";

/** Cleans and formats role names, removing demo prefixes and capitalizing words (e.g. 'Demo: accounts' -> 'Accounts', 'Demo: administration' -> 'Administrators'). */
function cleanRoleName(name: string): string {
  if (!name) return name;
  const stripped = name.replace(/^demo:\s*/i, "").trim();
  if (!stripped) return name;
  const lower = stripped.toLowerCase();
  if (lower === "administration" || lower === "admin" || lower === "administrators") {
    return "Administrators";
  }
  if (lower === "accounts" || lower === "account") {
    return "Accounts";
  }
  const words = stripped.split(/[\s_]+/).map((w) => {
    if (w.toLowerCase() === "and") return "&";
    return w.charAt(0).toUpperCase() + w.slice(1);
  });
  return words.join(" ");
}

/**
 * The role catalogue (21A section 8, "Roles"; M1-08), read only: the entity's own roles first
 * and the Federation's templates last, each with the permission codes it grants.
 */
export function RolesPage() {
  const t = useT();
  const { locale } = useIntl();
  const api = useAdminApi();
  const roles = useQuery({ queryKey: ["party", "roles"], queryFn: () => api.listRoles() });

  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [expandedRoles, setExpandedRoles] = useState<Set<string>>(new Set());

  const toggleRoleExpanded = (roleId: string) => {
    setExpandedRoles((prev) => {
      const next = new Set(prev);
      if (next.has(roleId)) {
        next.delete(roleId);
      } else {
        next.add(roleId);
      }
      return next;
    });
  };

  const hasFilters = search.trim() !== "" || typeFilter !== "" || statusFilter !== "";

  const clearFilters = () => {
    setSearch("");
    setTypeFilter("");
    setStatusFilter("");
  };

  const totalRoles = (roles.data ?? []).length;
  const templateRoles = (roles.data ?? []).filter((r) => r.template).length;
  const ownRoles = (roles.data ?? []).filter((r) => !r.template).length;

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (roles.data ?? []).filter((role) => {
      if (typeFilter === "template" && !role.template) return false;
      if (typeFilter === "own" && role.template) return false;
      if (statusFilter && role.status !== statusFilter) return false;
      if (q) {
        const rawName = roleName(role, locale).toLowerCase();
        const cleanName = cleanRoleName(rawName);
        const matchName = rawName.includes(q) || cleanName.toLowerCase().includes(q) || role.nameEn.toLowerCase().includes(q);
        const matchPerm = role.permissions.some((p) => {
          const info = getPermissionInfo(p.permissionCode);
          return (
            p.permissionCode.toLowerCase().includes(q) ||
            info.label.toLowerCase().includes(q) ||
            info.description.toLowerCase().includes(q)
          );
        });
        if (!matchName && !matchPerm) return false;
      }
      return true;
    });
  }, [roles.data, search, typeFilter, statusFilter, locale]);

  return (
    <main className="shell-page">
      <PageHeader icon="society" title={t("party.roles.title").text} />
      {roles.isLoading && <p>{t("party.list.loading").text}</p>}
      {roles.isError && <p role="alert">{refusalOf(roles.error, t("party.error.generic").text).text}</p>}
      {roles.isSuccess && roles.data.length === 0 && <p>{t("party.roles.empty").text}</p>}

      {roles.isSuccess && roles.data.length > 0 && (
        <>
          <form role="search" onSubmit={(e) => e.preventDefault()} className="roles-filter-bar">
            <div className="roles-filter-bar__search">
              <label className="modern-field">
                <span className="modern-field__label party-filter-label">{t("party.filter.search").text}</span>
                <input
                  type="search"
                  className="party-filter-input"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder={t("party.roles.filter.search").text}
                />
              </label>
            </div>
            <div className="roles-filter-bar__control">
              <label className="modern-field">
                <span className="modern-field__label party-filter-label">{t("party.roles.filter.type").text}</span>
                <CustomSelect
                  value={typeFilter}
                  onChange={(val) => setTypeFilter(val)}
                  ariaLabel={t("party.roles.filter.type").text}
                  options={[
                    { value: "", label: t("party.roles.filter.type.any").text },
                    { value: "template", label: t("party.roles.filter.type.template").text },
                    { value: "own", label: t("party.roles.filter.type.own").text }
                  ]}
                />
              </label>
            </div>
            <div className="roles-filter-bar__control">
              <label className="modern-field">
                <span className="modern-field__label party-filter-label">{t("party.roles.filter.status").text}</span>
                <CustomSelect
                  value={statusFilter}
                  onChange={(val) => setStatusFilter(val)}
                  ariaLabel={t("party.roles.filter.status").text}
                  options={[
                    { value: "", label: t("party.roles.filter.status.any").text },
                    { value: "ACTIVE", label: t("party.roles.status.ACTIVE").text },
                    { value: "RETIRED", label: t("party.roles.status.RETIRED").text }
                  ]}
                />
              </label>
            </div>
            {hasFilters && (
              <div className="roles-filter-bar__actions">
                <button
                  type="button"
                  className="roles-clear-btn"
                  onClick={clearFilters}
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    aria-hidden="true"
                  >
                    <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
                    <path d="M3 3v5h5" />
                  </svg>
                  <span>{t("party.roles.reset_filter").text}</span>
                </button>
              </div>
            )}
          </form>

          <div className="roles-summary-bar">
            <div className="roles-summary-counts">
              <span className="roles-summary-badge">
                <span className="roles-summary-dot roles-summary-dot--total" aria-hidden="true" />
                {t("party.roles.count_total", undefined, { count: totalRoles }).text}
              </span>
              <span className="roles-summary-separator" aria-hidden="true">·</span>
              <span className="roles-summary-badge">
                <span className="roles-summary-dot roles-summary-dot--template" aria-hidden="true" />
                {t("party.roles.count_templates", undefined, { count: templateRoles }).text}
              </span>
              <span className="roles-summary-separator" aria-hidden="true">·</span>
              <span className="roles-summary-badge">
                <span className="roles-summary-dot roles-summary-dot--own" aria-hidden="true" />
                {t("party.roles.count_own", undefined, { count: ownRoles }).text}
              </span>
            </div>
            {hasFilters && (
              <span className="roles-summary-filtered">
                {t("party.roles.showing_filtered", undefined, { count: filtered.length, total: totalRoles }).text}
              </span>
            )}
          </div>

          {filtered.length === 0 && (
            <div className="party-empty-card">
              <p className="party-empty-text">{t("party.roles.no_match").text}</p>
            </div>
          )}

          <div className="roles-grid">
            {filtered.map((role) => {
              const name = cleanRoleName(roleName(role, locale));
              const isExpanded = expandedRoles.has(role.roleId);
              const permLimit = 6;
              const hasMore = role.permissions.length > permLimit;
              const visiblePermissions = isExpanded ? role.permissions : role.permissions.slice(0, permLimit);

              return (
                <article
                  key={role.roleId}
                  className={`modern-role-card ${role.template ? "modern-role-card--template" : "modern-role-card--own"}`}
                  aria-label={name}
                >
                  <div className="modern-role-card__header">
                    <div className="modern-role-card__left">
                      <div className="modern-role-card__avatar" aria-hidden="true">
                        {role.template ? (
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <polygon points="12 2 2 7 12 12 22 7 12 2" />
                            <polyline points="2 17 12 22 22 17" />
                            <polyline points="2 12 12 17 22 12" />
                          </svg>
                        ) : (
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                            <circle cx="8.5" cy="7" r="4" />
                            <line x1="20" y1="8" x2="20" y2="14" />
                            <line x1="23" y1="11" x2="17" y2="11" />
                          </svg>
                        )}
                      </div>
                      <div className="modern-role-card__title-group">
                        <h2 className="modern-role-card__title">{name}</h2>
                        <div className="modern-role-card__subtitle">
                          <span>{t(`party.roles.class.${role.roleClass}`).text}</span>
                          <span aria-hidden="true">·</span>
                          <span>{t("party.roles.version", undefined, { version: role.version }).text}</span>
                          <span aria-hidden="true">·</span>
                          <span>{t("party.roles.permissions_count", undefined, { count: role.permissions.length }).text}</span>
                        </div>
                      </div>
                    </div>

                    <div className="modern-role-card__right">
                      <span className={`role-type-pill ${role.template ? "role-type-pill--template" : "role-type-pill--own"}`}>
                        {t(role.template ? "party.roles.template" : "party.roles.own").text}
                      </span>
                      <StateChip
                        state={role.status === "ACTIVE" ? "issued" : "void"}
                        label={t(`party.roles.status.${role.status}`).text}
                      />
                    </div>
                  </div>

                  <div className="role-capabilities-summary">
                    <span className="role-capabilities-summary__tag">{t("party.roles.duties").text}</span>
                    <span className="role-capabilities-summary__text">{getRoleDuties(role.nameEn, role.permissions, t)}</span>
                  </div>

                  <div className="modern-role-card__body">
                    {role.permissions.length === 0 ? (
                      <p className="party-alert-text">{t("party.roles.no_permissions").text}</p>
                    ) : (
                      <>
                        <div className="modern-role-card__perm-top">
                          <span>{t("party.roles.permissions_of", undefined, { role: name }).text}</span>
                          {hasMore && (
                            <button
                              type="button"
                              className="modern-role-card__toggle-link"
                              onClick={() => toggleRoleExpanded(role.roleId)}
                              aria-expanded={isExpanded}
                            >
                              {isExpanded
                                ? t("party.roles.show_less").text
                                : t("party.roles.show_all", undefined, { count: role.permissions.length }).text}
                            </button>
                          )}
                        </div>

                        <ul className="role-perm-grid" aria-label={t("party.roles.permissions_of", undefined, { role: name }).text}>
                          {visiblePermissions.map((permission) => {
                            const info = getPermissionInfo(permission.permissionCode);
                            const label = t(`party.roles.perm.${permission.permissionCode}`, info.label).text;
                            return (
                              <li key={permission.permissionCode}>
                                <div
                                  className={`modern-perm-tile ${role.template ? "modern-perm-tile--template" : "modern-perm-tile--own"}`}
                                  title={`${permission.permissionCode} — ${info.description}`}
                                >
                                  <svg
                                    className="modern-perm-tile__check"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2.5"
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    aria-hidden="true"
                                  >
                                    <polyline points="20 6 9 17 4 12" />
                                  </svg>
                                  <span className="modern-perm-tile__label">{label}</span>
                                </div>
                              </li>
                            );
                          })}
                        </ul>
                      </>
                    )}
                  </div>
                </article>
              );
            })}
          </div>
        </>
      )}
    </main>
  );
}
