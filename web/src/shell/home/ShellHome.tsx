import { Link } from "react-router-dom";
import { usePermissions } from "../auth/PermissionsContext";
import { useScope } from "../scope/useScope";
import { useT } from "../i18n/useT";
import { navItemsFor } from "../modules/assemble";
import type { ModuleDefinition } from "../modules/ModuleDefinition";
import { NavIcon } from "../nav/NavIcon";

export function ShellHome({ modules }: { modules: ModuleDefinition[] }) {
  const permissions = usePermissions();
  const scope = useScope();
  const t = useT();

  const items = permissions ? navItemsFor(modules, permissions) : [];
  const entityName = scope?.entityName || scope?.entityShortId || t("shell.home.default_entity").text;

  return (
    <main className="shell-home">
      <section className="shell-home-hero">
        <div className="shell-home-hero__content">
          <div className="shell-home-hero__badge">
            <span className="shell-home-hero__status-dot" aria-hidden="true" />
            <span>{t("shell.home.badge").text}</span>
          </div>
          <h1 className="shell-home-hero__title">
            {t("shell.home.welcome", undefined, { entity: entityName }).text}
          </h1>
          <p className="shell-home-hero__subtitle">{t("shell.home.welcome_sub").text}</p>
        </div>
      </section>

      <section className="shell-home-kpis">
        <div className="shell-home-kpi-card">
          <div className="shell-home-kpi-icon shell-home-kpi-icon--network">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
              <circle cx="9" cy="7" r="4" />
              <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
              <path d="M16 3.13a4 4 0 0 1 0 7.75" />
            </svg>
          </div>
          <div className="shell-home-kpi-info">
            <h3 className="shell-home-kpi-title">{t("shell.home.kpi.network_title").text}</h3>
            <p className="shell-home-kpi-desc">{t("shell.home.kpi.network_desc").text}</p>
          </div>
        </div>

        <div className="shell-home-kpi-card">
          <div className="shell-home-kpi-icon shell-home-kpi-icon--sync">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67" />
            </svg>
          </div>
          <div className="shell-home-kpi-info">
            <h3 className="shell-home-kpi-title">{t("shell.home.kpi.sync_title").text}</h3>
            <p className="shell-home-kpi-desc">{t("shell.home.kpi.sync_desc").text}</p>
          </div>
        </div>

        <div className="shell-home-kpi-card">
          <div className="shell-home-kpi-icon shell-home-kpi-icon--security">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
              <path d="M7 11V7a5 5 0 0 1 10 0v4" />
            </svg>
          </div>
          <div className="shell-home-kpi-info">
            <h3 className="shell-home-kpi-title">{t("shell.home.kpi.security_title").text}</h3>
            <p className="shell-home-kpi-desc">{t("shell.home.kpi.security_desc").text}</p>
          </div>
        </div>
      </section>

      <section className="shell-home-modules-section">
        <div className="shell-home-section-header">
          <div>
            <h2 className="shell-home-section-title">{t("shell.home.quick_access").text}</h2>
            <p className="shell-home-section-subtitle">{t("shell.home.quick_access_sub").text}</p>
          </div>
        </div>

        {items.length === 0 ? (
          <p className="shell-home-empty">{t(permissions ? "shell.nav.empty" : "shell.permissions.loading").text}</p>
        ) : (
          <div className="shell-home-grid">
            {items.map((item) => (
              <Link key={item.to} to={item.to} className="shell-home-card" title={t(item.labelId).text}>
                <div className="shell-home-card__icon-wrap">
                  <NavIcon to={item.to} />
                </div>
                <div className="shell-home-card__content">
                  <span className="shell-home-card__title">{t(item.labelId).text}</span>
                  <span className="shell-home-card__action">
                    <span>{t("shell.home.open_module").text}</span>
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M5 12h14" />
                      <path d="m12 5 7 7-7 7" />
                    </svg>
                  </span>
                </div>
              </Link>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}
