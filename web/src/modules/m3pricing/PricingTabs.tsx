import type { ReactNode } from "react";
import { NavLink } from "react-router-dom";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import "./pricing.css";

/**
 * The screens of the pricing module (23A section 8), one link each: the trade lists, the society's
 * shelf prices, the gazette of control prices, the MRP policy, discount rules, pricing reviews,
 * and the markdown queue. The shell has one navigation entry for the module; this row leads from
 * it to each screen.
 */
export function PricingTabs() {
  const t = useT();
  const canViewReviews = useHasPermission("prc.review.view");

  const tabs: Array<{ to: string; labelId: string; end: boolean; show: boolean; icon: ReactNode }> = [
    {
      to: "/pricing",
      labelId: "pricing.tab.trade",
      end: true,
      show: true,
      icon: (
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <path d="M4 5h9l7 7-8 8-8-8V5Z" />
          <circle cx="9" cy="10" r="1.5" />
        </svg>
      )
    },
    {
      to: "/pricing/shelf",
      labelId: "pricing.tab.shelf",
      end: false,
      show: true,
      icon: (
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <path d="M3 9h18" />
          <path d="M3 15h18" />
        </svg>
      )
    },
    {
      to: "/pricing/gazette",
      labelId: "pricing.tab.gazette",
      end: false,
      show: true,
      icon: (
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <path d="M4 22h16a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 1-2 2Zm0 0a2 2 0 0 1-2-2v-9c0-1.1.9-2 2-2h2" />
          <path d="M18 14h-8" />
          <path d="M15 18h-5" />
          <path d="M10 6h8v4h-8V6Z" />
        </svg>
      )
    },
    {
      to: "/pricing/mrp-policy",
      labelId: "pricing.tab.mrp_policy",
      end: false,
      show: true,
      icon: (
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
          <polyline points="9 12 11 14 15 10" />
        </svg>
      )
    },
    {
      to: "/pricing/rules",
      labelId: "pricing.tab.rules",
      end: false,
      show: true,
      icon: (
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
          <rect x="8" y="2" width="8" height="4" rx="1" ry="1" />
          <polyline points="9 14 11 16 15 12" />
        </svg>
      )
    },
    {
      to: "/pricing/reviews",
      labelId: "pricing.tab.reviews",
      end: false,
      show: canViewReviews,
      icon: (
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <circle cx="11" cy="11" r="8" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
          <path d="m9 11 2 2 4-4" />
        </svg>
      )
    },
    {
      to: "/pricing/markdown",
      labelId: "pricing.tab.markdown",
      end: false,
      show: true,
      icon: (
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <line x1="19" y1="5" x2="5" y2="19" />
          <circle cx="6.5" cy="6.5" r="2.5" />
          <circle cx="17.5" cy="17.5" r="2.5" />
        </svg>
      )
    }
  ].filter((tab) => tab.show);

  return (
    <div className="pricing-tabs-wrapper">
      <nav className="pricing-tabs" aria-label={t("pricing.tabs").text}>
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            end={tab.end}
            className={({ isActive }) => (isActive ? "pricing-tab pricing-tab--active" : "pricing-tab")}
          >
            <span className="pricing-tab__icon" aria-hidden="true">
              {tab.icon}
            </span>
            <span className="pricing-tab__label">{t(tab.labelId).text}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
